package io.github.arun0009.pulse.shutdown;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Blocks Spring's shutdown sequence until the OpenTelemetry batch span processor has flushed any
 * queued spans, capped by {@code pulse.shutdown.otel-flush-timeout}.
 *
 * <p>Without this, the {@link io.opentelemetry.sdk.trace.export.BatchSpanProcessor}'s internal
 * queue is dropped at JVM exit — which is exactly when you most want to see the final spans
 * (graceful drain on rolling deploy, the request that triggered shutdown, etc.).
 *
 * <p>Implemented as a {@link SmartLifecycle} with the lowest phase so it runs <em>before</em>
 * web servers stop accepting traffic, ensuring spans created during the drain still get exported.
 */
public final class PulseOtelShutdownLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PulseOtelShutdownLifecycle.class);

    private final @Nullable OpenTelemetrySdk sdk;
    private final ShutdownProperties config;
    private volatile boolean running = false;

    public PulseOtelShutdownLifecycle(@Nullable OpenTelemetrySdk sdk, ShutdownProperties config) {
        this.sdk = sdk;
        this.config = config;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (sdk == null || !config.otelFlushEnabled()) return;
        long timeoutMs = config.otelFlushTimeout().toMillis();
        log.info("Pulse: flushing OTel exporters (timeout {}ms) before shutdown", timeoutMs);

        CompletableResultCode flush = sdk.getSdkTracerProvider().forceFlush();
        flush.join(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!flush.isSuccess()) {
            log.warn("Pulse: OTel exporter flush did not complete within {}ms — some spans may be lost", timeoutMs);
        }
        // Deliberately do NOT call sdk.shutdown() here. Spring Boot's OpenTelemetry auto-config
        // registers the SDK as a managed bean with destroyMethod="close", which itself invokes
        // shutdown(). Calling shutdown() now would race or double-close the BatchSpanProcessor /
        // exporters. Our role is only to *block* the lifecycle until the flush has drained the
        // queue; the actual shutdown belongs to the bean that owns the SDK.
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Run before web server lifecycle (Boot's WebServerStartStopLifecycle is at SMART_LIFECYCLE_PHASE).
        // Returning a small phase ensures Pulse stops near the start of shutdown but after user beans.
        return Integer.MIN_VALUE / 2;
    }
}
