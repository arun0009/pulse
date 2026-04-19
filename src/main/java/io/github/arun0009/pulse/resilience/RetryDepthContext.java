package io.github.arun0009.pulse.resilience;

import org.jspecify.annotations.Nullable;

/**
 * Per-thread retry-depth counter used by Pulse to detect retry amplification across services.
 *
 * <p>The depth is the count of retry attempts that contributed to the <em>current</em> in-flight
 * request, summed across the entire call chain. Two sources increment it:
 *
 * <ol>
 *   <li>Inbound: the {@link RetryDepthFilter} reads the {@code Pulse-Retry-Depth} header (or
 *       whatever {@code pulse.retry.header-name} is configured to) and seeds the local counter
 *       with the upstream's value.
 *   <li>Local: {@link RetryObservation} bumps the counter every time a Resilience4j {@code Retry}
 *       reports an attempt — so a service that retries N times raises the depth by N.
 * </ol>
 *
 * <p>Outbound HTTP/Kafka propagation re-emits the current depth as a header so the next hop
 * inherits it. The result is a single integer that compounds through the call graph, which
 * Pulse compares against {@code pulse.retry.amplification-threshold} to detect cascades.
 *
 * <p>Static accessor mirrors {@link io.github.arun0009.pulse.guardrails.TimeoutBudget#current()}
 * so user code reads the depth without learning a new pattern.
 */
public final class RetryDepthContext {

    private RetryDepthContext() {}

    private static final ThreadLocal<@Nullable Integer> CURRENT = new ThreadLocal<>();

    /** Sets the depth for the current thread. Pass {@code null} to clear. */
    public static void set(@Nullable Integer depth) {
        if (depth == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(depth);
        }
    }

    /** Removes the thread-local entry. */
    public static void clear() {
        CURRENT.remove();
    }

    /** Current depth, or {@code 0} if no value has been seeded for this thread. */
    public static int current() {
        Integer value = CURRENT.get();
        return value == null ? 0 : value;
    }

    /** Atomically increments the depth and returns the new value. */
    public static int increment() {
        int next = current() + 1;
        CURRENT.set(next);
        return next;
    }
}
