package io.github.arun0009.pulse.core;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Predicate that decides whether a Pulse feature should apply to a given inbound HTTP request.
 *
 * <p>Used as the runtime form of any {@code enabled-when:} configuration block on a Pulse feature
 * (trace-guard, cardinality firewall, PII masking, etc.). Pulse resolves the configured matcher
 * once at startup — declarative criteria become a {@code DeclarativeRequestMatcher} instance, a
 * configured {@code bean: name} reference becomes a delegating wrapper around the user's bean —
 * and hands the resulting matcher to each filter that owns an {@code enabled-when} field.
 *
 * <p>The contract is intentionally request-only: a matcher must not consult mutable state
 * (counters, the response, baggage that downstream code mutates) so the same matcher instance
 * can be safely shared across all in-flight requests on all threads.
 *
 * <p>Implementations must be inexpensive — {@link #matches(HttpServletRequest)} is invoked on
 * every request, before any feature work. Heavy logic (regex compilation, bean lookups, JSON
 * parsing) belongs in the constructor / factory, not in {@code matches}.
 *
 * @since 1.1.0
 */
@FunctionalInterface
public interface PulseRequestMatcher {

    /**
     * Returns {@code true} when the owning feature should run for this request, {@code false}
     * to skip the feature entirely. Must be side-effect-free and must not throw — a matcher that
     * cannot evaluate (e.g. a missing header it requires) should return the safe default for the
     * caller, which for Pulse features is {@code true} (i.e. fail-open: the feature still runs).
     */
    boolean matches(HttpServletRequest request);

    /** Singleton matcher that always returns {@code true}; used as the default when no rule is configured. */
    PulseRequestMatcher ALWAYS = request -> true;
}
