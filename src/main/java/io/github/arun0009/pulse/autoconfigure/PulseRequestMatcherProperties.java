package io.github.arun0009.pulse.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * Declarative criteria for "should this feature run for this request?". Bound from any
 * {@code pulse.<feature>.enabled-when:} block — see {@code TraceGuardProperties#enabledWhen}
 * for the first feature that consumes it.
 *
 * <p>Semantics across fields are AND: every populated field must agree before the matcher
 * returns {@code true}. Within a multi-valued field (e.g. {@link #headerEquals()}), all entries
 * must agree. An empty / unset matcher always matches (i.e. the feature runs unconditionally,
 * which is Pulse's pre-1.1 behaviour and the safe default).
 *
 * <p>{@link #bean()} is an escape hatch for imperative logic that declarative rules cannot
 * express ("active only between 02:00–04:00 UTC for tenants on the free plan"). When set, the
 * declarative fields are ignored and the named {@code PulseRequestMatcher} bean is invoked
 * directly.
 *
 * @param headerEquals    request header values that must all match exactly (case-insensitive on
 *                        header names per the servlet spec). Header absent → not a match.
 * @param headerNotEquals request header values that must NOT match. Useful for "skip when
 *                        client-id=test-client-id" — if any listed header equals its forbidden
 *                        value, the matcher returns {@code false}. Missing headers count as
 *                        not-equal (i.e. they pass).
 * @param headerPrefix    request header values that must start with the given prefix.
 * @param pathMatches     request URI prefixes; the request path must start with at least one.
 * @param pathExcludes    request URI prefixes that must NOT match. Takes precedence over
 *                        {@link #pathMatches()}.
 * @param bean            optional bean name of a {@link io.github.arun0009.pulse.core.PulseRequestMatcher}
 *                        implementation that fully replaces the declarative criteria.
 *
 * @since 1.1.0
 */
public record PulseRequestMatcherProperties(
        @DefaultValue Map<String, String> headerEquals,
        @DefaultValue Map<String, String> headerNotEquals,
        @DefaultValue Map<String, String> headerPrefix,
        @DefaultValue List<String> pathMatches,
        @DefaultValue List<String> pathExcludes,
        @Nullable String bean) {

    /** Convenience: returns {@code true} when nothing has been configured (the common case). */
    public boolean isEmpty() {
        return (headerEquals == null || headerEquals.isEmpty())
                && (headerNotEquals == null || headerNotEquals.isEmpty())
                && (headerPrefix == null || headerPrefix.isEmpty())
                && (pathMatches == null || pathMatches.isEmpty())
                && (pathExcludes == null || pathExcludes.isEmpty())
                && (bean == null || bean.isBlank());
    }

    /**
     * Convenience factory for an empty matcher block — equivalent to "no rules configured" so the
     * owning feature always runs. Useful in tests and in code that constructs property records
     * by hand.
     */
    public static PulseRequestMatcherProperties empty() {
        return new PulseRequestMatcherProperties(
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.List.of(),
                java.util.List.of(),
                null);
    }
}
