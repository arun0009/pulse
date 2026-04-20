package io.github.arun0009.pulse.dependencies;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;

/**
 * Dependency health map — turns every outbound HTTP/Kafka call into a per-dependency RED
 * (rate / errors / duration) signal so an operator can answer "which downstream is hurting
 * me?" without opening N dashboards.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.dependencies")
public record DependenciesProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Map<String, String> map,
        @DefaultValue("unknown") @NotBlank String defaultName,
        @DefaultValue("20") @Min(1) int fanOutWarnThreshold,
        @DefaultValue @Valid PulseRequestMatcherProperties enabledWhen,
        @DefaultValue @Valid Health health) {

    /**
     * Topology-aware health configuration. Infers dependency state from the existing
     * dependency RED metrics (never calls the downstream's health endpoint).
     */
    public record Health(
            @DefaultValue("true") boolean enabled,
            @DefaultValue({}) List<String> critical,

            @DefaultValue("0.05") @DecimalMin("0.0") @DecimalMax("1.0") double errorRateThreshold,

            @DefaultValue("false") boolean down) {}
}
