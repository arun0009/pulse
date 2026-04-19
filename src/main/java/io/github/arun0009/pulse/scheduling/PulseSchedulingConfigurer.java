package io.github.arun0009.pulse.scheduling;

import io.github.arun0009.pulse.async.PulseTaskDecorator;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Wraps every {@code @Scheduled} task in {@link PulseTaskDecorator} so MDC and OTel context are
 * propagated when scheduled work fires. Without this, scheduled tasks log without a {@code
 * traceId} and any cross-thread MDC enrichment is lost.
 *
 * <p>Spring's {@link SchedulingConfigurer} contract gives us a hook into the
 * {@link ScheduledTaskRegistrar} before tasks start dispatching. We always replace the registrar's
 * scheduler with a {@link ContextPropagatingTaskScheduler} that wraps the application's
 * {@link TaskScheduler} bean — whether that's the user's own or Pulse's managed default
 * (registered as a Spring-managed bean by {@code PulseAutoConfiguration} so its threads are
 * shut down cleanly on context close).
 */
public final class PulseSchedulingConfigurer implements SchedulingConfigurer {

    private final TaskScheduler scheduler;

    public PulseSchedulingConfigurer(TaskScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        TaskScheduler existing = registrar.getScheduler();
        TaskScheduler target = (existing != null) ? existing : scheduler;
        registrar.setTaskScheduler(new ContextPropagatingTaskScheduler(target));
    }
}
