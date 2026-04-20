package io.github.arun0009.pulse.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * Pulse-shipped profile presets.
 *
 * <p>Pulse ships {@code application-pulse-dev.yml}, {@code application-pulse-prod.yml},
 * {@code application-pulse-test.yml} and {@code application-pulse-canary.yml} as
 * <em>standard Spring profile files</em>. When {@link #autoApply()} is {@code true} (the
 * default) and Pulse sees {@code dev}, {@code prod}, {@code test} or {@code canary} in the
 * active profiles without the corresponding {@code pulse-*} profile, it appends the matching
 * {@code pulse-<env>} profile so the preset gets loaded too.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.profile-presets")
public record ProfilePresetsProperties(
        @DefaultValue("true") boolean autoApply,
        @DefaultValue Map<String, String> presets) {}
