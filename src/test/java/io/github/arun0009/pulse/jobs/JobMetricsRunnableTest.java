package io.github.arun0009.pulse.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavior of the per-execution wrapper that emits {@code pulse.jobs.*} metrics and updates the
 * {@link JobRegistry}. Tests exercise the contract end-to-end against a real
 * {@link SimpleMeterRegistry} so any drift between counter / timer / gauge wiring would surface.
 */
class JobMetricsRunnableTest {

    private MeterRegistry meterRegistry;
    private JobRegistry jobRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        jobRegistry = new JobRegistry();
    }

    @Test
    void successful_run_increments_success_counter_and_records_duration_and_updates_registry() {
        Runnable target = new NamedTask("nightly-rollup");
        new JobMetricsRunnable(target, meterRegistry, jobRegistry).run();

        assertThat(meterRegistry
                        .counter("pulse.jobs.executions", Tags.of("job", "NamedTask", "outcome", "success"))
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry
                        .timer("pulse.jobs.duration", Tags.of("job", "NamedTask", "outcome", "success"))
                        .count())
                .isEqualTo(1L);
        assertThat(jobRegistry.snapshot().get("NamedTask").successCount()).isEqualTo(1);
        assertThat(jobRegistry.snapshot().get("NamedTask").lastSuccessAt()).isNotNull();
    }

    @Test
    void failed_run_increments_failure_counter_records_failure_in_registry_and_rethrows() {
        Runnable target = new ThrowingTask(new IllegalStateException("kaboom"));

        assertThatThrownBy(() -> new JobMetricsRunnable(target, meterRegistry, jobRegistry).run())
                .as("Pulse must rethrow so Spring's scheduler sees the failure — silent swallow"
                        + " is the worst kind of observability bug")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("kaboom");

        assertThat(meterRegistry
                        .counter("pulse.jobs.executions", Tags.of("job", "ThrowingTask", "outcome", "failure"))
                        .count())
                .isEqualTo(1.0);
        JobRegistry.JobSnapshot snap = jobRegistry.snapshot().get("ThrowingTask");
        assertThat(snap.failureCount()).isEqualTo(1);
        assertThat(snap.lastFailureCause()).isEqualTo("IllegalStateException: kaboom");
    }

    @Test
    void in_flight_gauge_drops_back_to_zero_after_run_even_when_run_throws() {
        Runnable target = new ThrowingTask(new RuntimeException("x"));
        try {
            new JobMetricsRunnable(target, meterRegistry, jobRegistry).run();
        } catch (RuntimeException ignored) {
            // expected
        }
        assertThat(meterRegistry
                        .find("pulse.jobs.in_flight")
                        .tags("job", "ThrowingTask")
                        .gauge()
                        .value())
                .as("in_flight must decrement in finally block, otherwise a single failure leaks the gauge")
                .isEqualTo(0.0);
    }

    @Test
    void resolves_scheduled_method_runnable_to_class_hash_method_name() throws Exception {
        // Spring wraps every @Scheduled method in a ScheduledMethodRunnable; that's where the
        // job names that show up in dashboards come from. A regression in name resolution would
        // shatter every consumer's dashboards, so the resolver is asserted here directly.
        var method = SampleJobs.class.getDeclaredMethod("doNightlyRollup");
        ScheduledMethodRunnable runnable = new ScheduledMethodRunnable(new SampleJobs(), method);
        assertThat(JobMetricsRunnable.resolveJobName(runnable)).isEqualTo("SampleJobs#doNightlyRollup");
    }

    @Test
    void falls_back_to_class_name_for_lambda_or_anonymous_runnable() {
        Runnable lambda = () -> {};
        // Lambda class name is non-deterministic across JDK versions but always contains "Lambda".
        assertThat(JobMetricsRunnable.resolveJobName(lambda)).isNotBlank();
    }

    @Test
    void increments_execution_counters_independently_per_outcome_so_failure_rate_is_computable() {
        Runnable success = new NamedTask("ok");
        Runnable failure = new ThrowingTask(new RuntimeException());

        new JobMetricsRunnable(success, meterRegistry, jobRegistry).run();
        new JobMetricsRunnable(success, meterRegistry, jobRegistry).run();
        try {
            new JobMetricsRunnable(failure, meterRegistry, jobRegistry).run();
        } catch (RuntimeException ignored) {
        }

        assertThat(meterRegistry
                        .counter("pulse.jobs.executions", Tags.of("job", "NamedTask", "outcome", "success"))
                        .count())
                .isEqualTo(2.0);
        assertThat(meterRegistry
                        .counter("pulse.jobs.executions", Tags.of("job", "ThrowingTask", "outcome", "failure"))
                        .count())
                .isEqualTo(1.0);
    }

    /** Simple named runnable so the simple-class-name fallback is deterministic. */
    private static final class NamedTask implements Runnable {
        @SuppressWarnings("unused")
        private final String name;

        NamedTask(String name) {
            this.name = name;
        }

        @Override
        public void run() {}
    }

    private static final class ThrowingTask implements Runnable {
        private final RuntimeException toThrow;

        ThrowingTask(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public void run() {
            throw toThrow;
        }
    }

    /** Holder for a stable Method reference used by the ScheduledMethodRunnable test. */
    public static final class SampleJobs {
        public void doNightlyRollup() {}
    }
}
