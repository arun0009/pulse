package io.github.arun0009.pulse.resilience;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Retry amplification detection — propagates a hop counter through the call chain so
 * Pulse can detect "service A retries 3x → B retries 3x → C gets 9x its normal traffic"
 * cascades <em>before</em> they become an outage.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.retry")
public record RetryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("Pulse-Retry-Depth") @NotBlank String headerName,
        @DefaultValue("3") @Min(1) int amplificationThreshold) {}
