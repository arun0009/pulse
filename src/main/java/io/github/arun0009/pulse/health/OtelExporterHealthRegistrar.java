package io.github.arun0009.pulse.health;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wraps every {@link SpanExporter} bean in the application context with a
 * {@link LastSuccessSpanExporter} so the {@link OtelExporterHealthIndicator} has signal to
 * report on.
 *
 * <p>Implemented as a {@link BeanPostProcessor} rather than poking inside the OTel SDK with
 * reflection. Spring Boot's tracing auto-configuration ({@code OpenTelemetryTracingAutoConfiguration}
 * and {@code OtlpAutoConfiguration}) publishes each exporter as a regular bean and then assembles
 * the {@code SpanProcessor} pipeline from those beans — by intercepting the beans during
 * post-processing we replace them with our wrapper before the SDK ever sees them. The result is
 * the same observability outcome (last-success/last-failure tracking) without any
 * setAccessible/getDeclaredField calls.
 *
 * <p>The wrapper itself is a {@link LastSuccessSpanExporter}, which is itself a {@link
 * SpanExporter}, so the BPP is idempotent: an already-wrapped instance is never wrapped again.
 *
 * <p>This bean is intentionally argument-free so Spring can register it as an infrastructure
 * BPP without having to instantiate any other beans first — important because BPPs must be
 * available before the regular bean creation phase begins.
 */
public final class OtelExporterHealthRegistrar implements BeanPostProcessor {

    private final List<LastSuccessSpanExporter> tracked = new CopyOnWriteArrayList<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof LastSuccessSpanExporter alreadyWrapped) {
            if (!tracked.contains(alreadyWrapped)) {
                tracked.add(alreadyWrapped);
            }
            return bean;
        }
        if (bean instanceof SpanExporter spanExporter) {
            LastSuccessSpanExporter wrapped = new LastSuccessSpanExporter(spanExporter);
            tracked.add(wrapped);
            return wrapped;
        }
        return bean;
    }

    /**
     * Returns an immutable snapshot of every wrapped exporter observed so far. Safe to call from
     * any thread; the underlying list is copy-on-write.
     *
     * @return the wrapped exporters tracked by this registrar.
     */
    public List<LastSuccessSpanExporter> exporters() {
        return List.copyOf(tracked);
    }
}
