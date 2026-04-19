package io.github.arun0009.pulse.jobs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link Runnable} decorator that records job execution metrics in Micrometer and updates a
 * {@link JobRegistry} entry on every run.
 *
 * <p>Metrics emitted (cardinality bounded by {@code @Scheduled} method count — typically
 * single-digit per app, almost always under 100):
 *
 * <ul>
 *   <li>{@code pulse.jobs.executions} — counter, tagged {@code job} and {@code outcome=success|failure}.
 *   <li>{@code pulse.jobs.duration} — timer with the configured SLO buckets, tagged {@code job}
 *       and {@code outcome}. Recorded via {@link Timer.Sample} so the timer participates in
 *       histogram + percentile aggregation.
 *   <li>{@code pulse.jobs.in_flight} — gauge, tagged {@code job}. Useful for catching jobs that
 *       are silently overlapping (overrun) — for {@code @Scheduled(fixedRate=…)} this should
 *       almost always be 0 or 1; sustained &gt;1 indicates the job is running longer than its
 *       interval and Spring is queuing executions.
 * </ul>
 *
 * <p>{@link JobRegistry} stores the same observations in heap form so the actuator endpoint and
 * {@link JobsHealthIndicator} can answer "when did this job last succeed?" without scraping
 * Prometheus.
 *
 * <p>Failures are <em>logged at WARN</em> with the throwable attached but rethrown so Spring's
 * scheduler still sees the failure and applies its own behavior (cancel for one-shots, continue
 * for fixed-rate). Pulse never silently swallows.
 */
public final class JobMetricsRunnable implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(JobMetricsRunnable.class);

    private final Runnable delegate;
    private final String jobName;
    private final MeterRegistry registry;
    private final JobRegistry jobRegistry;
    private final AtomicInteger inFlight;

    public JobMetricsRunnable(Runnable delegate, MeterRegistry registry, JobRegistry jobRegistry) {
        this.delegate = delegate;
        this.jobName = resolveJobName(delegate);
        this.registry = registry;
        this.jobRegistry = jobRegistry;
        // Gauge backed by AtomicInteger so we can increment/decrement without recomputing.
        // Pre-register with value 0 so the gauge appears in /actuator/prometheus output even
        // before the first run — empty time-series make alerting brittle.
        this.inFlight = registry.gauge(
                "pulse.jobs.in_flight", Tags.of("job", jobName), new AtomicInteger(), AtomicInteger::get);
    }

    /**
     * Resolves a stable, low-cardinality job name from the runnable. {@link ScheduledMethodRunnable}
     * is the common case — Spring wraps every {@code @Scheduled} method in one — so we extract
     * {@code Class#method}. Falls back to the runnable's class name (which is typically the
     * cron expression's lambda or anonymous class) for unrecognized shapes.
     *
     * <p>Public so {@link JobRegistry}'s tests and the actuator endpoint can use the same name
     * resolution rule and avoid drift.
     */
    public static String resolveJobName(Runnable runnable) {
        if (runnable instanceof ScheduledMethodRunnable smr) {
            Method method = smr.getMethod();
            String className = method.getDeclaringClass().getSimpleName();
            return className + "#" + method.getName();
        }
        return runnable.getClass().getSimpleName();
    }

    @Override
    public void run() {
        Timer.Sample sample = Timer.start(registry);
        inFlight.incrementAndGet();
        long startNanos = System.nanoTime();
        try {
            delegate.run();
            long elapsed = System.nanoTime() - startNanos;
            recordSuccess(sample, elapsed);
        } catch (RuntimeException | Error t) {
            long elapsed = System.nanoTime() - startNanos;
            recordFailure(sample, t, elapsed);
            // Rethrow so Spring's scheduler sees the failure. We don't want Pulse to act as a
            // silent swallower of exceptions — that's the worst kind of observability bug.
            throw t;
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void recordSuccess(Timer.Sample sample, long elapsedNanos) {
        Tags tags = Tags.of("job", jobName, "outcome", "success");
        sample.stop(registry.timer("pulse.jobs.duration", tags));
        Counter.builder("pulse.jobs.executions").tags(tags).register(registry).increment();
        jobRegistry.updateOnSuccess(jobName, elapsedNanos);
    }

    private void recordFailure(Timer.Sample sample, Throwable cause, long elapsedNanos) {
        Tags tags = Tags.of("job", jobName, "outcome", "failure");
        sample.stop(registry.timer("pulse.jobs.duration", tags));
        Counter.builder("pulse.jobs.executions").tags(tags).register(registry).increment();
        jobRegistry.updateOnFailure(jobName, cause, elapsedNanos);
        log.warn(
                "pulse.job_failed job={} duration_ms={} cause={}",
                jobName,
                TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                cause.getClass().getSimpleName(),
                cause);
    }
}
