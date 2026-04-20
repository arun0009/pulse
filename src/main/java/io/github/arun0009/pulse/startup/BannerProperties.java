package io.github.arun0009.pulse.startup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Startup banner that prints active Pulse subsystems and their settings. */
@Validated
@ConfigurationProperties(prefix = "pulse.banner")
public record BannerProperties(@DefaultValue("true") boolean enabled) {}
