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
 * <em>standard Spring profile files</em>. Auto-apply is <strong>off</strong> by default
 * because silently adding {@code pulse-prod} (and its 10% sampling rate) when a host
 * profile is named {@code prod} is a surprising behaviour change. Set
 * {@link #autoApply()} to {@code true} — or activate {@code pulse-prod} yourself — to
 * load a preset.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.profile-presets")
public record ProfilePresetsProperties(
        @DefaultValue("false") boolean autoApply,
        @DefaultValue Map<String, String> presets) {}
