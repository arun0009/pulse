package io.github.arun0009.pulse.test;

import io.github.arun0009.pulse.enforcement.PulseEnforcementMode;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test configuration that supplies an in-memory OpenTelemetry SDK so spans and span events can be
 * captured and asserted against without a backing exporter. Wired automatically by {@link
 * PulseTest}; can also be {@code @Import}ed onto a hand-rolled {@code @SpringBootTest}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PulseTestConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public InMemorySpanExporter pulseTestSpanExporter() {
        return InMemorySpanExporter.create();
    }

    /**
     * In-memory {@link OpenTelemetry} that records spans into the {@link InMemorySpanExporter}
     * bean. {@code @Primary} so it shadows the production SDK during tests without requiring the
     * application to exclude its real SDK bean. Still gated by
     * {@link ConditionalOnMissingBean} so a test can {@code @Import} a different SDK stand-in.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean
    public OpenTelemetry pulseTestOpenTelemetry(InMemorySpanExporter exporter) {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    @Bean
    @ConditionalOnMissingBean
    public PulseTestHarness pulseTestHarness(
            InMemorySpanExporter spanExporter,
            MeterRegistry meterRegistry,
            ObjectProvider<PulseEnforcementMode> enforcementMode) {
        return new PulseTestHarness(spanExporter, meterRegistry, enforcementMode.getIfAvailable());
    }
}
