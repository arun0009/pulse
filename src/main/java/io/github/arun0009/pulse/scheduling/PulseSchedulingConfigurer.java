package io.github.arun0009.pulse.scheduling;

import io.github.arun0009.pulse.async.PulseTaskDecorator;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Wraps every {@code @Scheduled} task in {@link PulseTaskDecorator} so MDC and OTel context are
 * propagated when scheduled work fires. Without this, scheduled tasks log without a {@code
 * traceId} and any cross-thread MDC enrichment is lost.
 *
 * <p>Spring's {@link SchedulingConfigurer} contract gives us the chance to intercept the
 * {@link TaskScheduler} before it starts dispatching. If the application has supplied its own
 * {@link ThreadPoolTaskScheduler} we ask it to use {@code PulseTaskDecorator} as its
 * {@code customizer} only when one isn't already set; if the scheduler is opaque (a custom
 * implementation), we don't touch it — Pulse never silently overrides user wiring.
 */
public final class PulseSchedulingConfigurer implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        TaskScheduler existing = resolveScheduler(registrar);
        if (existing instanceof ThreadPoolTaskScheduler tpts) {
            // Spring's TaskDecorator is set on TaskExecutor — for TaskScheduler we wrap each
            // submitted Runnable. Override the registrar's scheduler with a wrapper that does so.
            registrar.setTaskScheduler(new ContextPropagatingTaskScheduler(tpts));
            return;
        }
        if (existing != null) {
            registrar.setTaskScheduler(new ContextPropagatingTaskScheduler(existing));
            return;
        }
        // No scheduler set — install a Pulse-managed one with sensible defaults so @Scheduled
        // tasks always have context. This is the "zero-config" path most apps fall into.
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("pulse-scheduled-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        registrar.setTaskScheduler(new ContextPropagatingTaskScheduler(scheduler));
    }

    private static @Nullable TaskScheduler resolveScheduler(ScheduledTaskRegistrar registrar) {
        return registrar.getScheduler();
    }
}
