package io.github.arun0009.pulse.jobs;

import io.github.arun0009.pulse.async.PulseTaskDecorator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * {@link TaskScheduler} decorator that wraps every submitted {@link Runnable} in two layers:
 *
 * <ol>
 *   <li>{@link JobMetricsRunnable} — innermost, so the timer measures only the actual work
 *       (excluding context restoration overhead).
 *   <li>{@link PulseTaskDecorator} — outermost, so MDC + OTel context is restored before metrics
 *       and logging run. This means the WARN line for a job failure carries the same {@code
 *       traceId} / {@code service} / {@code env} fields as the rest of the application's logs.
 * </ol>
 *
 * <p>Replaces — and supersedes — the older {@code ContextPropagatingTaskScheduler}: that class
 * only did context propagation; this one does context propagation <em>and</em> metrics. Pulse's
 * autoconfig wires this as the active scheduler when {@code pulse.jobs.enabled=true} (default).
 *
 * <p>Why decorate the {@code TaskScheduler} rather than the underlying {@link
 * java.util.concurrent.ScheduledExecutorService}? Spring's {@code TaskScheduler} contract does
 * not surface a {@code TaskDecorator} hook (unlike {@code TaskExecutor}), so wrapping at the
 * scheduler level is the only place we can intercept every {@code @Scheduled} firing without
 * modifying user beans.
 */
public final class InstrumentedTaskScheduler implements TaskScheduler {

    private final TaskScheduler delegate;
    private final MeterRegistry registry;
    private final JobRegistry jobRegistry;
    private final PulseTaskDecorator contextDecorator = new PulseTaskDecorator();

    public InstrumentedTaskScheduler(TaskScheduler delegate, MeterRegistry registry, JobRegistry jobRegistry) {
        this.delegate = delegate;
        this.registry = registry;
        this.jobRegistry = jobRegistry;
    }

    private Runnable wrap(Runnable task) {
        // Order matters: metrics innermost (so timer measures pure work), context outermost
        // (so logs / metrics emitted from the metrics layer carry MDC).
        Runnable instrumented = new JobMetricsRunnable(task, registry, jobRegistry);
        return contextDecorator.decorate(instrumented);
    }

    @Override
    public ScheduledFuture<?> schedule(@NonNull Runnable task, @NonNull Trigger trigger) {
        ScheduledFuture<?> result = delegate.schedule(wrap(task), trigger);
        if (result == null) {
            throw new IllegalStateException("TaskScheduler.schedule(Trigger) returned null");
        }
        return result;
    }

    @Override
    public ScheduledFuture<?> schedule(@NonNull Runnable task, @NonNull Instant startTime) {
        return delegate.schedule(wrap(task), startTime);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
            @NonNull Runnable task, @NonNull Instant startTime, @NonNull Duration period) {
        return delegate.scheduleAtFixedRate(wrap(task), startTime, period);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(@NonNull Runnable task, @NonNull Duration period) {
        return delegate.scheduleAtFixedRate(wrap(task), period);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
            @NonNull Runnable task, @NonNull Instant startTime, @NonNull Duration delay) {
        return delegate.scheduleWithFixedDelay(wrap(task), startTime, delay);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(@NonNull Runnable task, @NonNull Duration delay) {
        return delegate.scheduleWithFixedDelay(wrap(task), delay);
    }

    // The Date / primitive-long overloads (schedule(Runnable, Date), scheduleAtFixedRate(Runnable, long), …)
    // are deprecated on TaskScheduler and ship as default methods that delegate to the Instant / Duration
    // versions above — so wrapping happens transparently without us re-implementing the deprecated API.
}
