package io.github.arun0009.pulse.dependencies;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * Maps an outbound URI (or raw host) to the logical {@code dep} tag value used on every
 * {@code pulse.dependency.*} meter.
 *
 * <h2>Chain-of-responsibility (since 2.0)</h2>
 *
 * <p>Pulse 2.0 removes the "single bean replaces everything" model and treats every
 * {@code DependencyClassifier} bean in the application context as one link in an ordered
 * chain. For each call:
 *
 * <ol>
 *   <li>Spring sorts every {@code DependencyClassifier} bean by {@link
 *       org.springframework.core.annotation.Order @Order} (lower = earlier; default is
 *       {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE}).
 *   <li>The composite calls {@link #classify(URI)} on each in turn.
 *   <li>The first non-{@code null} result wins.
 *   <li>If every link returns {@code null}, the host-table {@link DependencyResolver}
 *       (registered as the terminal classifier) returns the configured
 *       {@code pulse.dependencies.default-name}.
 * </ol>
 *
 * <p>This means a custom classifier can be <em>additive</em> rather than total: only override
 * the cases you care about and return {@code null} otherwise. Pre-2.0 implementations that
 * returned a non-null value for every input still work — they simply always win the chain
 * race when registered earlier than the terminal resolver.
 *
 * <h2>Examples</h2>
 *
 * <p>Path-aware classifier that participates in the chain (returns {@code null} to delegate):
 *
 * <pre>
 * &#064;Bean
 * &#064;Order(0)
 * DependencyClassifier paymentApiClassifier() {
 *     return new DependencyClassifier() {
 *         &#064;Override public String classify(URI uri) {
 *             if (uri.getPath() != null &amp;&amp; uri.getPath().startsWith("/api/v1/payments/")) {
 *                 return "payment-api-v1";
 *             }
 *             return null;
 *         }
 *         &#064;Override public String classifyHost(String host) {
 *             return null;
 *         }
 *     };
 * }
 * </pre>
 *
 * <p>Implementations must be cheap (called on every outbound call), thread-safe, and must
 * never throw — return {@code null} on failure so the next link gets a chance and cardinality
 * stays bounded even if the classifier hits an edge case.
 *
 * @since 1.1.0 (chain semantics added in 2.0.0)
 */
public interface DependencyClassifier {

    /**
     * Returns the logical {@code dep} tag for an outbound URI, or {@code null} to delegate to
     * the next classifier in the chain.
     */
    @Nullable String classify(URI uri);

    /**
     * Returns the logical {@code dep} tag from a raw host string (used by Kafka + OkHttp), or
     * {@code null} to delegate to the next classifier in the chain.
     */
    @Nullable String classifyHost(String host);
}
