package io.github.arun0009.pulse.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Resilience4j auto-instrumentation. Attaches event consumers to {@code CircuitBreakerRegistry},
 * {@code RetryRegistry}, and {@code BulkheadRegistry} that turn state transitions, retries,
 * and rejections into counters + span events + structured log lines.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.resilience")
public record ResilienceProperties(@DefaultValue("true") boolean enabled) {}
