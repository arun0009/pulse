package io.github.arun0009.pulse.exception;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * RFC 7807 ProblemDetail responses with traceId + requestId surfaced.
 *
 * <p>{@link #enabledWhen()} provides per-request gating: when the matcher returns
 * {@code false} for the current request, Pulse still produces a baseline ProblemDetail (so the
 * caller still gets a structured 500), but skips fingerprinting, the MDC stamp, the span
 * attribute, the metric increment, and the structured WARN log.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.exception-handler")
public record ExceptionHandlerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue @Valid PulseRequestMatcherProperties enabledWhen) {}
