package io.github.arun0009.pulse.slo;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Computes a live, in-process projection of how each declared SLO is tracking against its target,
 * read directly from the Micrometer registry — no Prometheus query round-trip required.
 *
 * <p>The projector backs the "SLO" panel in {@code /actuator/pulse} and {@code /actuator/pulseui}
 * so that even before Prometheus rules are applied, operators can see at a glance whether the
 * service is meeting its promises. It complements (does not replace) {@link SloRuleGenerator},
 * which is the production-grade alerting story.
 *
 * <p>The projection uses Micrometer's lifetime totals on {@code http.server.requests} (or whatever
 * meter the SLO targets). It is therefore a "since process start" view — for true rolling-window
 * compliance, rely on the generated Prometheus recording rules. The local view answers the
 * question "is anything obviously broken right now?" and is sufficient for desk-side smoke
 * checks.
 */
public final class SloProjector {

    private final SloProperties config;
    private final MeterRegistry registry;

    public SloProjector(SloProperties config, MeterRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    public List<SloStatus> project() {
        List<SloStatus> out = new ArrayList<>();
        for (SloProperties.Objective o : config.objectives()) {
            out.add(projectOne(o));
        }
        return out;
    }

    private SloStatus projectOne(SloProperties.Objective o) {
        Search search = registry.find(o.meter());
        Collection<Timer> timers = search.timers();
        if (timers.isEmpty()) {
            return new SloStatus(o.name(), o.sli(), o.target(), Double.NaN, 0L, "no data");
        }

        long total = 0;
        long good = 0;

        if ("latency".equalsIgnoreCase(o.sli())) {
            if (o.threshold() == null) {
                return new SloStatus(o.name(), o.sli(), o.target(), Double.NaN, 0L, "missing threshold");
            }
            Duration threshold = o.threshold();
            for (Timer t : timers) {
                long count = t.count();
                total += count;
                // Histogram snapshot is best-effort: if the timer doesn't carry one (e.g. simple
                // registry without histogram enabled), we treat all-as-good — the Prometheus
                // rules computed by SloRuleGenerator are the authoritative path. This local
                // projection is a smoke check, not a billing system.
                long goodSamples = countLatencyGood(t, threshold);
                good += goodSamples;
            }
        } else {
            // availability: non-5xx out of total
            for (Timer t : timers) {
                long count = t.count();
                total += count;
                String status = t.getId().getTag("status");
                String outcome = t.getId().getTag("outcome");
                if (isGood(status, outcome)) {
                    good += count;
                }
            }
        }

        if (total == 0) {
            return new SloStatus(o.name(), o.sli(), o.target(), Double.NaN, 0L, "no traffic");
        }
        double currentRatio = (double) good / (double) total;
        return new SloStatus(o.name(), o.sli(), o.target(), currentRatio, total, classify(currentRatio, o.target()));
    }

    private static boolean isGood(
            @org.jspecify.annotations.Nullable String status, @org.jspecify.annotations.Nullable String outcome) {
        if (outcome != null && (outcome.equalsIgnoreCase("SUCCESS") || outcome.equalsIgnoreCase("CLIENT_ERROR"))) {
            return true;
        }
        if (status == null) return false;
        if (status.length() != 3) return true; // unknown status — don't double-count it as bad
        char first = status.charAt(0);
        return first != '5';
    }

    private static long countLatencyGood(Timer timer, Duration threshold) {
        var snapshot = timer.takeSnapshot();
        var bucketCounts = snapshot.histogramCounts();
        if (bucketCounts.length == 0) {
            // No histogram — fall back to comparing the mean. Conservative: only count it as good
            // if the mean is comfortably under threshold.
            double meanMs = timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS);
            return meanMs < threshold.toMillis() ? timer.count() : 0L;
        }
        double thresholdNanos = (double) threshold.toNanos();
        long good = 0;
        for (var bucket : bucketCounts) {
            if (bucket.bucket() <= thresholdNanos) {
                good = (long) bucket.count();
            }
        }
        return good;
    }

    private static String classify(double current, double target) {
        if (Double.isNaN(current)) return "unknown";
        if (current >= target) return "meeting";
        double errorBudget = 1.0 - target;
        double currentBurn = (1.0 - current) / errorBudget;
        if (currentBurn >= 14.4) return "burning-fast";
        if (currentBurn >= 6.0) return "burning-slow";
        return "below-target";
    }

    /**
     * Live view of one SLO's current compliance.
     *
     * @param name objective name as declared
     * @param sli {@code availability} or {@code latency}
     * @param target the configured fraction (e.g. {@code 0.999})
     * @param currentRatio observed good/total since process start; {@code NaN} when no data
     * @param sampleCount total samples observed
     * @param status one of {@code meeting}, {@code below-target}, {@code burning-slow},
     *     {@code burning-fast}, {@code no data}, {@code no traffic}, {@code missing threshold}
     */
    public record SloStatus(
            String name, String sli, double target, double currentRatio, long sampleCount, String status) {

        public List<Tag> asTags() {
            return List.of(Tag.of("slo", name), Tag.of("status", status));
        }
    }
}
