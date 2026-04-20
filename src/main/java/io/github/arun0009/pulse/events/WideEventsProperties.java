package io.github.arun0009.pulse.events;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Wide-event API ({@link io.github.arun0009.pulse.events.SpanEvents}) — one call attaches
 * attributes to the active span, emits a structured INFO log, and (optionally) increments a
 * bounded counter.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.wide-events")
public record WideEventsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean counterEnabled,
        @DefaultValue("true") boolean logEnabled,
        @DefaultValue("pulse.events") @NotBlank String counterName,
        @DefaultValue("event") @NotBlank String logMessagePrefix) {}
