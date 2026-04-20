package io.github.arun0009.pulse.guardrails;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Cardinality firewall — caps the number of distinct tag values per meter to prevent
 * runaway-tag bill explosions. Excess values bucket to {@code OVERFLOW} and a one-time WARN
 * log line fires.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.cardinality")
public record CardinalityProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1000") @Positive int maxTagValuesPerMeter,
        @DefaultValue("OVERFLOW") @NotBlank String overflowValue,
        @DefaultValue({}) List<String> meterPrefixesToProtect,
        @DefaultValue({}) List<String> exemptMeterPrefixes) {}
