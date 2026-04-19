package io.github.arun0009.pulse.scheduling;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.function.UnaryOperator;

/**
 * Installs a Pulse-decorated {@link TaskScheduler} into Spring's
 * {@link ScheduledTaskRegistrar} so every {@code @Scheduled} task gains MDC + OTel context
 * propagation and (when jobs observability is enabled) execution metrics + a registry entry for
 * the {@code jobs} health indicator.
 *
 * <p>The actual wrapping decision lives in {@code PulseAutoConfiguration} and is supplied as a
 * {@link UnaryOperator}{@code <TaskScheduler>}: it might wrap with
 * {@link io.github.arun0009.pulse.jobs.InstrumentedTaskScheduler} (default — context + metrics)
 * or with {@link ContextPropagatingTaskScheduler} (context only, when {@code pulse.jobs.enabled=false}).
 * Keeping the wrapping policy outside the configurer means this class has no compile-time
 * dependency on the jobs subsystem and we don't need a second {@code SchedulingConfigurer} bean
 * to swap behavior.
 */
public final class PulseSchedulingConfigurer implements SchedulingConfigurer {

    private final TaskScheduler scheduler;
    private final UnaryOperator<TaskScheduler> wrapper;

    public PulseSchedulingConfigurer(TaskScheduler scheduler, UnaryOperator<TaskScheduler> wrapper) {
        this.scheduler = scheduler;
        this.wrapper = wrapper;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        TaskScheduler existing = registrar.getScheduler();
        TaskScheduler target = (existing != null) ? existing : scheduler;
        registrar.setTaskScheduler(wrapper.apply(target));
    }
}
