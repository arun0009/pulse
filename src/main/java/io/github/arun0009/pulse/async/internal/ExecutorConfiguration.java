package io.github.arun0009.pulse.async.internal;

import io.github.arun0009.pulse.async.AsyncProperties;
import io.github.arun0009.pulse.async.PulseTaskDecorator;
import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Wires Pulse's MDC + OTel context propagation into Spring's {@code applicationTaskExecutor}
 * <em>without</em> taking ownership of it.
 *
 * <p>Spring Boot 3.2+ already auto-configures an {@code applicationTaskExecutor} via
 * {@code TaskExecutionAutoConfiguration}, and that auto-config <em>auto-applies</em> any single
 * {@link TaskDecorator} bean it finds in the context. By exposing {@link PulseTaskDecorator} as
 * a bean, Pulse propagates context across {@code @Async} hops while leaving Boot's executor
 * defaults (and the user's {@code spring.task.execution.pool.*} properties) intact.
 *
 * <p>For applications that explicitly want Pulse to own a dedicated executor (e.g. for an isolated
 * tenant pool), set {@code pulse.async.dedicated-executor=true} and Pulse will register a
 * {@link ThreadPoolTaskExecutor} with the decorator pre-applied. Most applications should leave
 * this off and let Boot manage the pool.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
public class ExecutorConfiguration {

    /**
     * Exposes {@link PulseTaskDecorator} so Spring Boot's {@code TaskExecutionAutoConfiguration}
     * applies it to the auto-configured {@code applicationTaskExecutor}.
     */
    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    @ConditionalOnProperty(prefix = "pulse.async", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TaskDecorator pulseTaskDecorator() {
        return new PulseTaskDecorator();
    }

    /**
     * Optional: only registers a dedicated {@code taskExecutor} bean if the user explicitly opts in
     * via {@code pulse.async.dedicated-executor=true}. Default is off so Boot's auto-configured
     * executor wins.
     */
    @Bean
    @ConditionalOnProperty(prefix = "pulse.async", name = "dedicated-executor", havingValue = "true")
    @ConditionalOnMissingBean(name = "pulseDedicatedExecutor")
    public ThreadPoolTaskExecutor pulseDedicatedExecutor(AsyncProperties async) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        if (async.enabled()) {
            executor.setTaskDecorator(new PulseTaskDecorator());
        }
        executor.setCorePoolSize(async.corePoolSize());
        executor.setMaxPoolSize(async.maxPoolSize());
        executor.setQueueCapacity(async.queueCapacity());
        executor.setThreadNamePrefix(async.threadNamePrefix());
        executor.initialize();
        return executor;
    }
}
