package io.github.arun0009.pulse.exception;

/**
 * Computes a stable, low-cardinality fingerprint for a {@link Throwable}.
 *
 * <p>Pulse ships a default implementation backed by {@link ExceptionFingerprint} (SHA-256 over
 * the exception class plus the top stack frames of the root cause, truncated to 10 hex chars).
 * That default is good enough for greenfield apps. Teams that already have a stable error id
 * coming from elsewhere — Sentry's {@code event_id}, Honeybadger's {@code fault_id}, an
 * in-house bug-tracker key — can publish a single bean implementing this interface and Pulse
 * will use it everywhere a fingerprint is surfaced (span attribute, MDC, ProblemDetail, the
 * {@code pulse.errors.unhandled} counter tag).
 *
 * <pre>
 * &#064;Bean
 * ErrorFingerprintStrategy sentryFingerprint(SentryClient sentry) {
 *     return throwable -&gt; {
 *         SentryEvent event = sentry.lastEventFor(throwable);
 *         return event != null ? event.getEventId() : ExceptionFingerprint.of(throwable);
 *     };
 * }
 * </pre>
 *
 * <p>Implementations must be cheap (called on every unhandled exception), side-effect-free,
 * and must never throw. Returning a string longer than ~32 chars is fine but makes dashboards
 * harder to read.
 *
 * @since 1.1.0
 */
@FunctionalInterface
public interface ErrorFingerprintStrategy {

    /** Returns a stable, low-cardinality identifier for the given throwable. Never {@code null}. */
    String fingerprint(Throwable throwable);

    /** Pulse's built-in SHA-256-of-stack-frames default. */
    ErrorFingerprintStrategy DEFAULT = ExceptionFingerprint::of;
}
