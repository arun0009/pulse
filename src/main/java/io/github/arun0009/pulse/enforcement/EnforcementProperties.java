package io.github.arun0009.pulse.enforcement;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Process-wide enforce-vs-observe gate. {@link PulseEnforcementMode.Mode#ENFORCING} (default)
 * runs every Pulse guardrail normally; {@link PulseEnforcementMode.Mode#DRY_RUN} keeps
 * observation but disables enforcement (trace-context guard never returns 4xx, cardinality
 * firewall counts overflows but lets the original tag value through, etc.).
 *
 * <p>The mode can be flipped at runtime via
 * {@code POST /actuator/pulse/enforcement} with body {@code {"value":"DRY_RUN"}}.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.enforcement")
public record EnforcementProperties(
        @DefaultValue("ENFORCING") @NotNull PulseEnforcementMode.Mode mode) {}
