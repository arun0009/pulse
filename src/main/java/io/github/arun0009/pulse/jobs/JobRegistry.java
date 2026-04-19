package io.github.arun0009.pulse.jobs;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory registry tracking the runtime state of every scheduled job Pulse has seen execute.
 *
 * <p>Populated lazily — a job appears here the first time it runs through Pulse's instrumented
 * scheduler, so jobs that never fired (misconfigured cron, disabled bean) deliberately do not
 * show up. This is the right behavior for {@link JobsHealthIndicator}: we cannot meaningfully
 * report health on a job we have never observed.
 *
 * <p>Concurrency: {@link #updateOnSuccess(String, long)} / {@link #updateOnFailure(String,
 * Throwable, long)} are called from scheduler threads; {@link #snapshot()} is called from
 * actuator endpoints + health checks. {@link ConcurrentHashMap} provides the right per-key
 * atomicity, and the per-job state is immutable inside each entry — counters are
 * {@link AtomicLong} and timestamps are written under the same {@code compute} call as the
 * counter increment so a snapshot never observes a "succeeded but counter unincremented" state.
 *
 * <p>Bounded growth: the registry keeps one entry per distinct job name. Job names derive from
 * {@link JobMetricsRunnable#resolveJobName(Runnable)} — typically {@code Class#method} — so the
 * cardinality is bounded by the number of {@code @Scheduled} methods in the application. Pulse
 * does not need an LRU here.
 */
public final class JobRegistry {

    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    /**
     * Records a successful execution. {@code durationNanos} is captured by the caller so the
     * timing observation includes work that happened before the registry update (e.g. counter
     * increments) — the registry's update cost is not double-counted.
     */
    public void updateOnSuccess(String jobName, long durationNanos) {
        jobs.compute(jobName, (k, existing) -> {
            JobState state = existing != null ? existing : new JobState();
            state.successCount.incrementAndGet();
            state.lastSuccessAt = Instant.now();
            state.lastDurationNanos = durationNanos;
            state.lastFailureCause = null; // clear on success so the registry doesn't show stale errors
            return state;
        });
    }

    public void updateOnFailure(String jobName, @Nullable Throwable cause, long durationNanos) {
        jobs.compute(jobName, (k, existing) -> {
            JobState state = existing != null ? existing : new JobState();
            state.failureCount.incrementAndGet();
            state.lastFailureAt = Instant.now();
            state.lastDurationNanos = durationNanos;
            state.lastFailureCause =
                    cause == null ? null : cause.getClass().getSimpleName() + ": " + cause.getMessage();
            return state;
        });
    }

    /**
     * Returns an immutable snapshot of all known jobs. The snapshot is point-in-time: subsequent
     * runs will not be reflected. Callers that need live values must call again.
     */
    public Map<String, JobSnapshot> snapshot() {
        Map<String, JobSnapshot> out = new java.util.LinkedHashMap<>();
        // Sort by name so actuator output is stable across calls — useful for diff-friendly
        // dashboards and for grep-style debugging.
        jobs.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            JobState s = e.getValue();
            out.put(
                    e.getKey(),
                    new JobSnapshot(
                            s.successCount.get(),
                            s.failureCount.get(),
                            s.lastSuccessAt,
                            s.lastFailureAt,
                            s.lastFailureCause,
                            s.lastDurationNanos));
        });
        return Collections.unmodifiableMap(out);
    }

    /** Mutable per-job state held inside the registry; never escapes. */
    private static final class JobState {
        final AtomicLong successCount = new AtomicLong();
        final AtomicLong failureCount = new AtomicLong();
        volatile @Nullable Instant lastSuccessAt;
        volatile @Nullable Instant lastFailureAt;
        volatile @Nullable String lastFailureCause;
        volatile long lastDurationNanos;
    }

    /**
     * Immutable point-in-time snapshot of a single job's state. Suitable for serialization to
     * actuator JSON. Fields are nullable: a never-succeeded job has a null {@code lastSuccessAt},
     * a never-failed job has a null {@code lastFailureAt} / {@code lastFailureCause}.
     */
    public record JobSnapshot(
            long successCount,
            long failureCount,
            @Nullable Instant lastSuccessAt,
            @Nullable Instant lastFailureAt,
            @Nullable String lastFailureCause,
            long lastDurationNanos) {}
}
