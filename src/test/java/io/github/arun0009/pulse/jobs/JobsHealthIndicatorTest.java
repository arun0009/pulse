package io.github.arun0009.pulse.jobs;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the page-out conditions of {@link JobsHealthIndicator}. Each test exercises a real
 * scenario an SRE will hit — fresh app, all-healthy, one stuck, never-succeeded — and asserts
 * the page-time payload (status + offending job name + last-success age) is present so the alert
 * narrative writes itself.
 */
class JobsHealthIndicatorTest {

    private final JobsProperties config = new JobsProperties(true, true, Duration.ofMinutes(10));

    @Test
    void no_observed_jobs_reports_up_with_observed_count_zero() {
        // Cold start. We deliberately do NOT report UNKNOWN here — that would fail readiness
        // probes for several minutes after every deploy on apps that don't run jobs immediately.
        JobsHealthIndicator indicator = new JobsHealthIndicator(new JobRegistry(), config);
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("observedJobs", 0);
    }

    @Test
    void recent_success_reports_up_with_age_in_details() {
        JobRegistry registry = new JobRegistry();
        new JobMetricsRunnable(() -> {}, new SimpleMeterRegistry(), registry).run();

        Health health = new JobsHealthIndicator(registry, config).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> jobs =
                (Map<String, Map<String, Object>>) health.getDetails().get("jobs");
        assertThat(jobs).hasSize(1);
        Map<String, Object> jobDetail = jobs.values().iterator().next();
        assertThat(jobDetail).containsKey("lastSuccessAt").containsKey("lastSuccessAgeMs");
    }

    @Test
    void never_succeeded_with_recorded_failure_reports_down_with_offender() {
        JobRegistry registry = new JobRegistry();
        registry.updateOnFailure("flaky-job", new RuntimeException("boom"), 5_000_000L);

        Health health = new JobsHealthIndicator(registry, config).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> jobs =
                (Map<String, Map<String, Object>>) health.getDetails().get("jobs");
        assertThat(jobs.get("flaky-job"))
                .containsEntry("status", "NEVER_SUCCEEDED")
                .containsEntry("lastFailureCause", "RuntimeException: boom");
    }

    @Test
    void stale_last_success_beyond_grace_period_reports_down_and_marks_job_stuck() {
        // Force a registry entry whose lastSuccess is well past the grace period. We can't
        // rewind the clock cleanly, so instead we configure the indicator with a tiny grace
        // period and let real time elapse — 1 ms is enough to guarantee the job is stuck.
        JobRegistry registry = new JobRegistry();
        new JobMetricsRunnable(() -> {}, new SimpleMeterRegistry(), registry).run();

        try {
            Thread.sleep(5);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        JobsProperties tightGrace = new JobsProperties(true, true, Duration.ofMillis(1));
        Health health = new JobsHealthIndicator(registry, tightGrace).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> jobs =
                (Map<String, Map<String, Object>>) health.getDetails().get("jobs");
        assertThat(jobs.values().iterator().next()).containsEntry("status", "STUCK");
    }

    @Test
    void includes_grace_period_in_details_so_operators_can_see_the_threshold() {
        Health health = new JobsHealthIndicator(new JobRegistry(), config).health();
        // Empty registry takes the early-return path, so add at least one job to exercise
        // the full assembly of the details map.
        JobRegistry r2 = new JobRegistry();
        new JobMetricsRunnable(() -> {}, new SimpleMeterRegistry(), r2).run();
        Health populated = new JobsHealthIndicator(r2, config).health();
        assertThat(populated.getDetails()).containsEntry("gracePeriod", "PT10M");
        // Sanity: the empty path has no gracePeriod (we returned UP early).
        assertThat(health.getDetails()).doesNotContainKey("gracePeriod");
    }
}
