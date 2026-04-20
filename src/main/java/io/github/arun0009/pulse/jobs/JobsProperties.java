package io.github.arun0009.pulse.jobs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Background-job observability — wraps every {@code @Scheduled} method (and any other
 * {@link Runnable} routed through Spring's {@link org.springframework.scheduling.TaskScheduler})
 * with metrics + a registry that powers the {@code jobs} health indicator.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.jobs")
public record JobsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean healthIndicatorEnabled,
        @DefaultValue("1h") Duration failureGracePeriod) {}
