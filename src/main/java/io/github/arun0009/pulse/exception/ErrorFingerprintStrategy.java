package io.github.arun0009.pulse.exception;

import org.jspecify.annotations.Nullable;

/**
 * Computes a stable, low-cardinality fingerprint for a {@link Throwable}.
 *
 * <h2>Chain-of-responsibility (since 2.0)</h2>
 *
 * <p>Pulse 2.0 removes the "single bean replaces everything" model. Every
 * {@code ErrorFingerprintStrategy} bean in the application context becomes one link in an
 * ordered chain. For each unhandled exception:
 *
 * <ol>
 *   <li>Spring sorts every {@code ErrorFingerprintStrategy} bean by
 *       {@link org.springframework.core.annotation.Order @Order}
 *       (lower = earlier; default is {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE}).
 *   <li>The composite calls {@link #fingerprint(Throwable)} on each in turn.
 *   <li>The first non-{@code null} result wins.
 *   <li>If every link returns {@code null}, the built-in {@link ExceptionFingerprint} hash
 *       (registered as the terminal strategy) returns a SHA-256 over the exception class +
 *       top stack frames so the chain always produces a value.
 * </ol>
 *
 * <p>This means a custom strategy can be <em>additive</em> rather than total: only override
 * the cases you care about and return {@code null} otherwise. Pre-2.0 implementations that
 * always returned a non-null value still work — they simply always win the chain race when
 * registered at default order.
 *
 * <h2>Examples</h2>
 *
 * <pre>
 * &#064;Bean
 * &#064;Order(0)
 * ErrorFingerprintStrategy sentryFingerprint(SentryClient sentry) {
 *     return throwable -&gt; {
 *         SentryEvent event = sentry.lastEventFor(throwable);
 *         return event != null ? event.getEventId() : null;
 *     };
 * }
 * </pre>
 *
 * <p>Implementations must be cheap (called on every unhandled exception), side-effect-free,
 * and must never throw — return {@code null} on failure so the next link gets a chance.
 * Returning a string longer than ~32 chars is fine but makes dashboards harder to read.
 *
 * @since 1.1.0 (chain semantics added in 2.0.0)
 */
@FunctionalInterface
public interface ErrorFingerprintStrategy {

    /**
     * Returns a stable, low-cardinality identifier for the given throwable, or {@code null} to
     * delegate to the next strategy in the chain.
     */
    @Nullable String fingerprint(Throwable throwable);

    /**
     * Pulse's built-in SHA-256-of-stack-frames default. Used as the terminal link in the
     * chain so every exception gets a fingerprint even when no user-supplied strategy
     * matched.
     */
    ErrorFingerprintStrategy DEFAULT = ExceptionFingerprint::of;
}
