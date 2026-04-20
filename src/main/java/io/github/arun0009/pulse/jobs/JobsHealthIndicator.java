package io.github.arun0009.pulse.jobs;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports the health of every observed scheduled job.
 *
 * <p>For each job in {@link JobRegistry}, the indicator computes age-since-last-success and
 * compares it against {@link JobsProperties#failureGracePeriod()} — a flat upper bound on
 * how long a job is permitted to go between successful runs before it is considered stuck. Pulse
 * deliberately does <em>not</em> try to introspect cron expressions or fixed-rate intervals from
 * Spring's {@code ScheduledTaskHolder}: that data is fragmented across cron / fixed-rate /
 * fixed-delay / trigger sources, frequently disagrees with the runtime cadence (when business
 * hours triggers fire only on weekdays, for example), and would silently misreport health for
 * any job that does not match the heuristic. A single tunable grace period is honest, simple,
 * and operators can override per-environment.
 *
 * <p>Status semantics:
 *
 * <ul>
 *   <li>{@code UP} — every observed job has succeeded within the grace period, OR no jobs have
 *       run yet (cold-started service).
 *   <li>{@code DOWN} — at least one observed job has not succeeded within the grace period and
 *       has either never succeeded or has a recorded failure that is older than its last
 *       success. Includes the offending job names + last-success timestamps in the details so
 *       the page-out narrative writes itself.
 * </ul>
 *
 * <p>A job that succeeded once and has not run again recently is treated as stale and reported
 * DOWN — that is the correct behavior for an SRE: a job that should run hourly and last ran
 * three days ago is broken even if its last run succeeded.
 */
public final class JobsHealthIndicator implements HealthIndicator {

    private final JobRegistry registry;
    private final JobsProperties config;

    public JobsHealthIndicator(JobRegistry registry, JobsProperties config) {
        this.registry = registry;
        this.config = config;
    }

    @Override
    public Health health() {
        Map<String, JobRegistry.JobSnapshot> jobs = registry.snapshot();
        if (jobs.isEmpty()) {
            // No jobs have run yet. We deliberately return UP rather than UNKNOWN: most apps
            // launch with no scheduled work pending, and forcing UNKNOWN would cause every
            // freshly-deployed pod to fail readiness for several minutes. Operators who want
            // strict "must have observed each job" semantics can set fail-fast via a custom
            // indicator that consults the ScheduledTaskHolder.
            return Health.up().withDetail("observedJobs", 0).build();
        }

        Duration grace = config.failureGracePeriod();
        long graceMs = grace.toMillis();
        long now = System.currentTimeMillis();

        Map<String, Object> jobDetails = new LinkedHashMap<>();
        boolean anyStuck = false;

        for (Map.Entry<String, JobRegistry.JobSnapshot> entry : jobs.entrySet()) {
            JobRegistry.JobSnapshot snap = entry.getValue();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("successCount", snap.successCount());
            detail.put("failureCount", snap.failureCount());
            if (snap.lastSuccessAt() != null) {
                detail.put("lastSuccessAt", snap.lastSuccessAt().toString());
                long ageMs = now - snap.lastSuccessAt().toEpochMilli();
                detail.put("lastSuccessAgeMs", ageMs);
                if (ageMs > graceMs) {
                    detail.put("status", "STUCK");
                    anyStuck = true;
                }
            } else {
                detail.put("status", "NEVER_SUCCEEDED");
                anyStuck = true;
            }
            if (snap.lastFailureCause() != null) {
                detail.put("lastFailureCause", snap.lastFailureCause());
                detail.put(
                        "lastFailureAt",
                        snap.lastFailureAt() != null ? snap.lastFailureAt().toString() : null);
            }
            jobDetails.put(entry.getKey(), detail);
        }

        Health.Builder builder = anyStuck ? Health.down() : Health.up();
        return builder.withDetail("gracePeriod", grace.toString())
                .withDetail("jobs", jobDetails)
                .build();
    }

    /** Exposed for tests so they can assert on the DOWN state without parsing JSON. */
    public boolean isStuck(JobRegistry.JobSnapshot snap, Instant now, Duration grace) {
        if (snap.lastSuccessAt() == null) return true;
        return now.toEpochMilli() - snap.lastSuccessAt().toEpochMilli() > grace.toMillis();
    }

    /** Exposed for tests / actuator wiring. */
    @SuppressWarnings("unused")
    public Status statusFor(JobRegistry.JobSnapshot snap, Instant now, Duration grace) {
        return isStuck(snap, now, grace) ? Status.DOWN : Status.UP;
    }
}
