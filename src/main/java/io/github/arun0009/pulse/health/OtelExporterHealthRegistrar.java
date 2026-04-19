package io.github.arun0009.pulse.health;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers the configured OpenTelemetry {@link SpanExporter}(s) on the registered
 * {@link OpenTelemetrySdk} and wraps each one with a {@link LastSuccessSpanExporter} so the
 * {@link OtelExporterHealthIndicator} has signal to report on.
 *
 * <p>The OTel SDK does not expose its registered exporters through a public API; this class
 * therefore uses targeted reflection on the {@code BatchSpanProcessor}'s {@code spanExporter}
 * field. The reflection is best-effort and gracefully degrades to a no-op when a future SDK
 * version changes the field name — in which case the health indicator simply reports
 * {@code UNKNOWN}, never failing startup.
 *
 * <p>Why reflection: the only other option is asking users to bean-define their exporter, which
 * defeats Pulse's "zero-config" promise. The reflection target is stable across all OTel SDK
 * 1.x releases so far. If the upstream SDK ever exposes a public accessor, this class collapses
 * to a one-line bean lookup.
 */
public final class OtelExporterHealthRegistrar implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(OtelExporterHealthRegistrar.class);

    private final @Nullable OpenTelemetrySdk sdk;
    private final List<LastSuccessSpanExporter> tracked = new ArrayList<>();

    public OtelExporterHealthRegistrar(@Nullable OpenTelemetrySdk sdk) {
        this.sdk = sdk;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (sdk == null) {
            log.debug("Pulse: no OpenTelemetrySdk bean found; OTel exporter health indicator will report UNKNOWN");
            return;
        }
        SdkTracerProvider tracerProvider = sdk.getSdkTracerProvider();
        try {
            Object activeProcessor = readField(tracerProvider, "activeSpanProcessor");
            collectExportersRecursive(activeProcessor);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.debug(
                    "Pulse: could not introspect OTel SpanProcessor for exporter wrapping ({}). "
                            + "Health indicator will report UNKNOWN.",
                    e.getMessage());
        }
    }

    private void collectExportersRecursive(@Nullable Object processor) throws ReflectiveOperationException {
        if (processor == null) return;
        // Composite span processor — recurse into children
        Object children = tryReadField(processor, "spanProcessors");
        if (children instanceof java.util.Collection<?> collection) {
            for (Object child : collection) {
                collectExportersRecursive(child);
            }
            return;
        }
        // BatchSpanProcessor — wrap the underlying exporter
        Object worker = tryReadField(processor, "worker");
        if (worker != null) {
            Object exporter = tryReadField(worker, "spanExporter");
            if (exporter instanceof SpanExporter spanExporter) {
                wrapAndReplace(processor, worker, spanExporter);
            }
        }
    }

    private void wrapAndReplace(Object processor, Object worker, SpanExporter exporter) {
        if (exporter instanceof LastSuccessSpanExporter alreadyWrapped) {
            tracked.add(alreadyWrapped);
            return;
        }
        LastSuccessSpanExporter wrapped = new LastSuccessSpanExporter(exporter);
        try {
            writeField(worker, "spanExporter", wrapped);
            tracked.add(wrapped);
        } catch (ReflectiveOperationException e) {
            log.debug("Pulse: could not install LastSuccessSpanExporter on processor {}", processor, e);
        }
    }

    public List<LastSuccessSpanExporter> exporters() {
        return List.copyOf(tracked);
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        Field f = findField(target.getClass(), name);
        if (f == null) {
            throw new NoSuchFieldException(name + " on " + target.getClass().getName());
        }
        f.setAccessible(true);
        Object value = f.get(target);
        if (value == null) throw new IllegalStateException("field " + name + " was null");
        return value;
    }

    private static @Nullable Object tryReadField(Object target, String name) {
        Field f = findField(target.getClass(), name);
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void writeField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field f = findField(target.getClass(), name);
        if (f == null) {
            throw new NoSuchFieldException(name + " on " + target.getClass().getName());
        }
        f.setAccessible(true);
        f.set(target, value);
    }

    private static @Nullable Field findField(Class<?> cls, String name) {
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
