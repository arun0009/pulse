package io.github.arun0009.pulse.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.util.List;

/**
 * Reports the OpenTelemetry trace exporter as healthy when at least one of the registered
 * {@link LastSuccessSpanExporter} instances has completed an export within
 * {@code pulse.health.otel-exporter-stale-after}.
 *
 * <p>The indicator returns:
 * <ul>
 *   <li>{@code UP} — at least one exporter has succeeded recently;</li>
 *   <li>{@code OUT_OF_SERVICE} — no exporter has ever completed a successful export (typically a
 *       cold-started service that hasn't emitted spans yet);</li>
 *   <li>{@code DOWN} — every exporter has at least one observed success but the most recent one
 *       is older than the staleness threshold (the OTel pipeline is stuck).</li>
 * </ul>
 *
 * <p>Wired only when {@code pulse.health.otel-exporter-enabled=true} (default). Apps that
 * intentionally idle for long stretches can disable it.
 */
public final class OtelExporterHealthIndicator implements HealthIndicator {

    public static final String STATUS_OUT_OF_SERVICE = "OUT_OF_SERVICE";

    private final List<LastSuccessSpanExporter> exporters;
    private final OtelExporterHealthProperties config;

    public OtelExporterHealthIndicator(List<LastSuccessSpanExporter> exporters, OtelExporterHealthProperties config) {
        this.exporters = exporters;
        this.config = config;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.unknown();
        if (exporters.isEmpty()) {
            return builder.status(Status.UNKNOWN)
                    .withDetail("reason", "no LastSuccessSpanExporter registered")
                    .build();
        }

        long now = System.currentTimeMillis();
        Duration staleAfter = config.otelExporterStaleAfter();
        long staleAfterMs = staleAfter.toMillis();

        boolean anyHealthy = false;
        boolean anyEverSucceeded = false;
        long bestAgeMs = Long.MAX_VALUE;
        long totalSuccess = 0;
        long totalFailure = 0;

        for (LastSuccessSpanExporter exp : exporters) {
            long lastSuccess = exp.lastSuccessEpochMillis();
            totalSuccess += exp.totalSuccessfulExports();
            totalFailure += exp.totalFailedExports();
            if (lastSuccess == 0) continue;
            anyEverSucceeded = true;
            long ageMs = now - lastSuccess;
            if (ageMs < bestAgeMs) bestAgeMs = ageMs;
            if (ageMs <= staleAfterMs) anyHealthy = true;
        }

        Health.Builder result;
        if (anyHealthy) {
            result = Health.up();
        } else if (!anyEverSucceeded) {
            result = Health.status(new Status(STATUS_OUT_OF_SERVICE));
        } else {
            result = Health.down();
        }

        result.withDetail("staleAfter", staleAfter.toString())
                .withDetail("totalSuccess", totalSuccess)
                .withDetail("totalFailure", totalFailure);
        if (bestAgeMs != Long.MAX_VALUE) {
            result.withDetail("lastSuccessAgeMs", bestAgeMs);
        }
        return result.build();
    }
}
