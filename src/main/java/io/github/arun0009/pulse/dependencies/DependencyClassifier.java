package io.github.arun0009.pulse.dependencies;

import java.net.URI;

/**
 * Maps an outbound URI (or raw host) to the logical {@code dep} tag value used on every
 * {@code pulse.dependency.*} meter.
 *
 * <p>Pulse's default classifier is {@link DependencyResolver}, which resolves the host through
 * the {@code pulse.dependencies.map} table (exact + leading-dot suffix) and falls back to
 * {@code pulse.dependencies.default-name} on no match. That covers most teams.
 *
 * <p>When the host-based table isn't enough — wildcard regions ({@code us-*.payments.internal}
 * → {@code payment-service}), URL-path-aware classification ({@code /api/v1/payments/*} →
 * {@code payment-api-v1} regardless of host), or pulling the name from request headers your
 * gateway already stamps — publish a single bean implementing this interface. Pulse will
 * consult it for every transport (RestTemplate, RestClient, WebClient, OkHttp, Kafka) and you
 * keep the rest of the dependency observability stack (RED metrics, fan-out, dependency-health
 * indicator) untouched.
 *
 * <pre>
 * &#064;Bean
 * DependencyClassifier customClassifier(DependencyResolver fallback) {
 *     return new DependencyClassifier() {
 *         &#064;Override public String classify(URI uri) {
 *             if (uri.getPath() != null &amp;&amp; uri.getPath().startsWith("/api/v1/payments/")) {
 *                 return "payment-api-v1";
 *             }
 *             return fallback.resolve(uri);
 *         }
 *         &#064;Override public String classifyHost(String host) {
 *             return fallback.resolveHost(host);
 *         }
 *     };
 * }
 * </pre>
 *
 * <p>Implementations must be cheap (called on every outbound call), thread-safe, and must never
 * throw — return the {@code default-name} on failure so cardinality stays bounded even if the
 * classifier hits an edge case.
 *
 * @since 1.1.0
 */
public interface DependencyClassifier {

    /** Returns the logical {@code dep} tag for an outbound URI. Never {@code null}. */
    String classify(URI uri);

    /** Returns the logical {@code dep} tag from a raw host string (used by Kafka + OkHttp). Never {@code null}. */
    String classifyHost(String host);
}
