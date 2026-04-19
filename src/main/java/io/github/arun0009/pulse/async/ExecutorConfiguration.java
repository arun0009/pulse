package io.github.arun0009.pulse.async;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * If the application has not declared its own {@code taskExecutor}, Pulse registers one with {@link
 * PulseTaskDecorator} pre-applied. Apps that already own their executor should apply the decorator
 * manually.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "pulse.async",
        name = "auto-configure-executor",
        havingValue = "true",
        matchIfMissing = true)
public class ExecutorConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor(PulseProperties properties) {
        var async = properties.async();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        if (async.propagationEnabled()) {
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
