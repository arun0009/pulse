package io.github.arun0009.pulse.async;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * MDC + OTel context propagation across {@code @Async}, {@code @Scheduled}, and other thread
 * hops.
 *
 * <p>By default Pulse <em>only</em> exposes a {@link org.springframework.core.task.TaskDecorator}
 * bean — Spring Boot's {@code TaskExecutionAutoConfiguration} auto-applies it to the standard
 * {@code applicationTaskExecutor}, so apps keep Boot's pool sizing and {@code
 * spring.task.execution.pool.*} configuration. Setting {@link #dedicatedExecutor()} to
 * {@code true} additionally registers a dedicated {@code pulseDedicatedExecutor} bean for
 * isolation needs.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.async")
public record AsyncProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("false") boolean dedicatedExecutor,
        @DefaultValue("8") @Positive int corePoolSize,
        @DefaultValue("32") @Positive int maxPoolSize,
        @DefaultValue("100") @PositiveOrZero int queueCapacity,
        @DefaultValue("pulse-") @NotBlank String threadNamePrefix,
        @DefaultValue("true") boolean scheduledPropagationEnabled) {}
