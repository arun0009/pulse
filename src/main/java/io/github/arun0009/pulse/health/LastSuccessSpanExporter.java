package io.github.arun0009.pulse.health;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorates an underlying {@link SpanExporter} and records the wall-clock time of the most
 * recent successful export. Used by {@link OtelExporterHealthIndicator} to detect stuck or
 * silently-failing exporters — the single most common reason production traces "vanish."
 *
 * <p>Failures are tracked separately so the health indicator can report
 * {@code lastSuccessAgeMillis} alongside {@code lastFailureAgeMillis}, giving operators an
 * actionable picture without touching the OTel SDK directly.
 *
 * <p>Wrapping is intentionally read-mostly: the exporter is the hot path for trace data, so
 * we avoid synchronization and use plain {@link AtomicLong} counters. The atomicity guarantee
 * here is "monotonic increasing" not "linearizable" — the indicator does not require the
 * latter.
 */
public final class LastSuccessSpanExporter implements SpanExporter {

    private final SpanExporter delegate;
    private final AtomicLong lastSuccessEpochMillis = new AtomicLong(0);
    private final AtomicLong lastFailureEpochMillis = new AtomicLong(0);
    private final AtomicLong totalSuccessfulExports = new AtomicLong(0);
    private final AtomicLong totalFailedExports = new AtomicLong(0);

    public LastSuccessSpanExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        CompletableResultCode result = delegate.export(spans);
        result.whenComplete(() -> {
            long now = System.currentTimeMillis();
            if (result.isSuccess()) {
                lastSuccessEpochMillis.set(now);
                totalSuccessfulExports.incrementAndGet();
            } else {
                lastFailureEpochMillis.set(now);
                totalFailedExports.incrementAndGet();
            }
        });
        return result;
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    public long lastSuccessEpochMillis() {
        return lastSuccessEpochMillis.get();
    }

    public long lastFailureEpochMillis() {
        return lastFailureEpochMillis.get();
    }

    public long totalSuccessfulExports() {
        return totalSuccessfulExports.get();
    }

    public long totalFailedExports() {
        return totalFailedExports.get();
    }
}
