package io.github.arun0009.pulse.dependencies;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Topology-aware {@link HealthIndicator} that infers downstream health from the caller-side RED
 * metrics Pulse already records (the {@code pulse.dependency.requests} counter and its
 * {@code outcome} tag). It performs <em>no</em> HTTP probes — there is no extra cost, no risk of
 * cascading failure, and no possibility of a probe storm at boot.
 *
 * <p>For each dependency listed in {@code pulse.dependencies.health.critical}, the indicator
 * walks the registry, sums the {@code SERVER_ERROR} and total request counts under the
 * {@code dep} tag, and reports {@code DEGRADED} (a non-standard {@link Status}) when the error
 * ratio crosses {@code pulse.dependencies.health.error-rate-threshold} or any
 * {@code SERVER_ERROR} is present without a successful counterpart yet. {@code UP} otherwise.
 *
 * <p>If {@code pulse.dependencies.health.down=true}, the threshold breach reports
 * {@code OUT_OF_SERVICE} instead — making the indicator participate in Kubernetes readiness
 * probes so the kubelet can shift traffic away from a pod whose critical dependency is
 * misbehaving. The default is the conservative {@code DEGRADED} so installing Pulse never
 * silently flips an existing service's readiness contract.
 *
 * <p>The indicator is the natural counterpart to Spring Boot's local {@code /actuator/health}:
 * Spring Boot answers "are my disks/JVM/database ok?", Pulse answers "are the things I depend
 * on ok, as far as I can tell from the calls I just made?".
 */
public final class DependencyHealthIndicator implements HealthIndicator {

    /** Non-standard status used for the conservative threshold breach. */
    public static final Status DEGRADED = new Status("DEGRADED");

    static final String REQUEST_METER = "pulse.dependency.requests";
    static final String DEP_TAG = "dep";
    static final String OUTCOME_TAG = "outcome";
    static final String SERVER_ERROR_OUTCOME = "SERVER_ERROR";

    private final MeterRegistry registry;
    private final List<String> critical;
    private final double errorRateThreshold;
    private final boolean reportDown;

    public DependencyHealthIndicator(MeterRegistry registry, DependenciesProperties.Health config) {
        this.registry = registry;
        this.critical = List.copyOf(config.critical());
        this.errorRateThreshold = config.errorRateThreshold();
        this.reportDown = config.down();
    }

    @Override
    public Health health() {
        Map<String, DepStats> stats = collect();
        Health.Builder builder = Health.up();
        Set<String> degraded = new LinkedHashSet<>();
        Map<String, Object> dependencies = new LinkedHashMap<>();

        for (String name : critical) {
            DepStats s = stats.getOrDefault(name, DepStats.EMPTY);
            double errorRate = s.errorRate();
            boolean breach = s.serverErrors > 0 && (s.total == 0 || errorRate >= errorRateThreshold);

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("status", breach ? "DEGRADED" : (s.total == 0 ? "UNOBSERVED" : "UP"));
            detail.put("errorRate", round(errorRate));
            detail.put("serverErrors", s.serverErrors);
            detail.put("totalRequests", s.total);
            dependencies.put(name, detail);

            if (breach) degraded.add(name);
        }

        builder.withDetail("dependencies", dependencies);
        builder.withDetail("threshold", errorRateThreshold);

        if (degraded.isEmpty()) return builder.build();

        Status status = reportDown ? Status.OUT_OF_SERVICE : DEGRADED;
        return Health.status(status)
                .withDetail("dependencies", dependencies)
                .withDetail("degradedDependencies", degraded)
                .withDetail("threshold", errorRateThreshold)
                .build();
    }

    private Map<String, DepStats> collect() {
        Map<String, DepStats.Mutable> map = new LinkedHashMap<>();
        for (Meter meter : registry.getMeters()) {
            if (!REQUEST_METER.equals(meter.getId().getName())) continue;
            if (!(meter instanceof Counter counter)) continue;
            String dep = tag(meter, DEP_TAG);
            String outcome = tag(meter, OUTCOME_TAG);
            if (dep == null) continue;
            DepStats.Mutable s = map.computeIfAbsent(dep, k -> new DepStats.Mutable());
            long count = (long) counter.count();
            s.total += count;
            if (SERVER_ERROR_OUTCOME.equals(outcome)) s.serverErrors += count;
        }
        Map<String, DepStats> out = new LinkedHashMap<>(map.size());
        map.forEach((k, v) -> out.put(k, v.snapshot()));
        return out;
    }

    private static @Nullable String tag(Meter meter, String key) {
        for (Tag t : meter.getId().getTagsAsIterable()) {
            if (t.getKey().equals(key)) return t.getValue();
        }
        return null;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private record DepStats(long total, long serverErrors) {
        static final DepStats EMPTY = new DepStats(0, 0);

        double errorRate() {
            return total == 0 ? 0.0 : (double) serverErrors / (double) total;
        }

        static final class Mutable {
            long total;
            long serverErrors;

            DepStats snapshot() {
                return new DepStats(total, serverErrors);
            }
        }
    }
}
