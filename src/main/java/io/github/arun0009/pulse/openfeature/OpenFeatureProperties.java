package io.github.arun0009.pulse.openfeature;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * OpenFeature integration. When the {@code dev.openfeature:sdk} is on the classpath, Pulse
 * registers a {@link PulseOpenFeatureMdcHook} that threads flag values onto MDC and stamps
 * OTel-semconv {@code feature_flag} span events.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.open-feature")
public record OpenFeatureProperties(@DefaultValue("true") boolean enabled) {}
