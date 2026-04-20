package io.github.arun0009.pulse.health;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Pulse's OTel-exporter health indicator — reports a degraded state when no span has been
 * successfully exported within {@link #otelExporterStaleAfter()}. Disable if your service
 * intentionally stays idle for long stretches.
 *
 * <p>Distinct from {@code DependenciesProperties.Health}, which is the per-downstream
 * RED-based health indicator.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.health")
public record OtelExporterHealthProperties(
        @DefaultValue("true") boolean otelExporterEnabled,
        @DefaultValue("5m") Duration otelExporterStaleAfter) {}
