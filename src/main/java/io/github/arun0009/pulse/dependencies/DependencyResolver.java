package io.github.arun0009.pulse.dependencies;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pulse's built-in {@link DependencyClassifier} — maps an outbound URI to a logical dependency
 * name (e.g. {@code api.payments.internal} → {@code payment-service}) using the
 * {@code pulse.dependencies.map} table.
 *
 * <p>Two resolution strategies are tried in order:
 *
 * <ol>
 *   <li>Exact host match against {@link DependenciesProperties#map()}.
 *   <li>Suffix match — useful for environment variants where the application configures
 *       {@code payments.internal: payment-service} and the actual hosts are
 *       {@code api.payments.internal}, {@code grpc.payments.internal}.
 * </ol>
 *
 * <p>If neither matches, {@link DependenciesProperties#defaultName()} is returned. The
 * default value of {@code "unknown"} keeps the metric tag bounded so Prometheus does not get a
 * cardinality bomb when calls go to dynamic hosts (S3, AWS APIs, etc.).
 *
 * <p>Resolution is case-insensitive on the host part because DNS is. Since 1.1, this class
 * also implements {@link DependencyClassifier} so a custom classifier can delegate to it for
 * the cases it doesn't want to override.
 */
public class DependencyResolver implements DependencyClassifier {

    private final Map<String, String> exactMap;
    private final Map<String, String> suffixMap;
    private final String defaultName;

    public DependencyResolver(DependenciesProperties config) {
        Map<String, String> exact = new LinkedHashMap<>();
        Map<String, String> suffix = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : config.map().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            String value = entry.getValue();
            exact.put(key, value);
            // Suffix entries make ".payments.internal" match every subdomain — opt-in by leading
            // dot so a literal "api.payments.internal" only matches that one host.
            if (key.startsWith(".")) {
                suffix.put(key, value);
            }
        }
        this.exactMap = exact;
        this.suffixMap = suffix;
        this.defaultName = config.defaultName();
    }

    /** Resolve the logical name for an outbound URI. Never returns null. */
    public String resolve(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isEmpty()) return defaultName;
        String lower = host.toLowerCase(Locale.ROOT);
        String exact = exactMap.get(lower);
        if (exact != null) return exact;
        for (Map.Entry<String, String> e : suffixMap.entrySet()) {
            if (lower.endsWith(e.getKey())) return e.getValue();
        }
        return defaultName;
    }

    /** Resolve from a raw host string (Kafka brokers, OkHttp Request#url etc.). */
    public String resolveHost(String host) {
        if (host == null || host.isEmpty()) return defaultName;
        String lower = host.toLowerCase(Locale.ROOT);
        String exact = exactMap.get(lower);
        if (exact != null) return exact;
        for (Map.Entry<String, String> e : suffixMap.entrySet()) {
            if (lower.endsWith(e.getKey())) return e.getValue();
        }
        return defaultName;
    }

    /** Default tag value used when a host is not in the map. Exposed for diagnostics. */
    public String defaultName() {
        return defaultName;
    }

    @Override
    public String classify(URI uri) {
        return resolve(uri);
    }

    @Override
    public String classifyHost(String host) {
        return resolveHost(host);
    }
}
