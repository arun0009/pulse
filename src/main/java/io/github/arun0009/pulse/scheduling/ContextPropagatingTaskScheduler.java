package io.github.arun0009.pulse.scheduling;

import io.github.arun0009.pulse.async.PulseTaskDecorator;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * {@link TaskScheduler} decorator that wraps every submitted {@link Runnable} in
 * {@link PulseTaskDecorator} so MDC + OTel context propagates from the submitting thread to the
 * scheduled execution.
 *
 * <p>Wraps each scheduling method instead of the underlying executor because Spring's
 * {@code TaskScheduler} contract does not expose a {@code TaskDecorator} hook the way
 * {@code TaskExecutor} does.
 *
 * <p>We do not override the {@code Date}- / {@code long}-millis default methods on
 * {@link TaskScheduler} — those defaults forward to the {@link Instant} / {@link Duration}
 * overloads above, which keeps context propagation correct without calling deprecated APIs on
 * the delegate.
 */
public final class ContextPropagatingTaskScheduler implements TaskScheduler {

    private final TaskScheduler delegate;
    private final PulseTaskDecorator decorator = new PulseTaskDecorator();

    public ContextPropagatingTaskScheduler(TaskScheduler delegate) {
        this.delegate = delegate;
    }

    private Runnable wrap(Runnable task) {
        return decorator.decorate(task);
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
}
