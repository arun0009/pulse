package io.github.arun0009.pulse.core;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Detect inbound requests missing trace-context headers.
 *
 * <p>{@link #enabledWhen()} provides per-request gating: the guard is skipped for requests
 * where the matcher returns {@code false}. Use it to bypass synthetic monitoring traffic,
 * smoke tests, or trusted internal callers without setting {@code enabled=false} globally.
 * The default is an empty matcher, which matches every request.
 *
 * <pre>
 * pulse:
 *   trace-guard:
 *     enabled: true
 *     enabled-when:
 *       header-not-equals:
 *         client-id: test-client-id
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "pulse.trace-guard")
public record TraceGuardProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("false") boolean failOnMissing,
        @DefaultValue({"/actuator", "/health", "/metrics"}) List<String> excludePathPrefixes,
        @DefaultValue @Valid PulseRequestMatcherProperties enabledWhen) {}
