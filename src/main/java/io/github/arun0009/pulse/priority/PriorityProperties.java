package io.github.arun0009.pulse.priority;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Request criticality propagation. Pulse extracts the priority from the configured inbound
 * header, normalizes it against the five-tier vocabulary
 * {@code critical, high, normal, low, background}, and re-emits it on every outbound call.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.priority")
public record PriorityProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("Pulse-Priority") @NotBlank String headerName,
        @DefaultValue("normal") @NotBlank String defaultPriority,
        @DefaultValue("true") boolean warnOnCriticalTimeoutExhaustion,
        @DefaultValue({}) List<String> tagMeters) {}
