package io.github.arun0009.pulse.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

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
 *
 * <p>The exporter list is supplied lazily so the indicator can be created before the SDK
 * builds the exporter pipeline — relevant because Spring may instantiate the indicator before
 * the trailing OTel beans are wired.
 */
public final class OtelExporterHealthIndicator implements HealthIndicator {

    public static final String STATUS_OUT_OF_SERVICE = "OUT_OF_SERVICE";

    private final Supplier<List<LastSuccessSpanExporter>> exporters;
    private final OtelExporterHealthProperties config;

    /**
     * Convenience constructor for tests and direct wiring with a fixed exporter list.
     */
    public OtelExporterHealthIndicator(List<LastSuccessSpanExporter> exporters, OtelExporterHealthProperties config) {
        this(() -> exporters, config);
    }

    /**
     * Production constructor: the supplier is queried on every {@link #health()} call so that
     * exporter beans created after the indicator (e.g. by Spring Boot's tracing auto-config)
     * are still observed.
     */
    public OtelExporterHealthIndicator(
            Supplier<List<LastSuccessSpanExporter>> exporters, OtelExporterHealthProperties config) {
        this.exporters = exporters;
        this.config = config;
    }

    @Override
    public Health health() {
        List<LastSuccessSpanExporter> snapshot = exporters.get();
        Health.Builder builder = Health.unknown();
        if (snapshot.isEmpty()) {
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

        for (LastSuccessSpanExporter exp : snapshot) {
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
