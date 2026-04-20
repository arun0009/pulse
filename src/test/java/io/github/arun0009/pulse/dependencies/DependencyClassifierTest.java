package io.github.arun0009.pulse.dependencies;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the 1.1 {@link DependencyClassifier} SPI lets users replace the host-table
 * default with a path-aware (or arbitrary) strategy, and that the default {@link
 * DependencyResolver} continues to work as a built-in classifier.
 */
class DependencyClassifierTest {

    private static final DependenciesProperties CONFIG = new DependenciesProperties(
            true,
            Map.of("api.payments.internal", "payment-service"),
            "unknown",
            20,
            PulseRequestMatcherProperties.empty(),
            new DependenciesProperties.Health(true, List.of(), 0.05, false));

    @Test
    void default_resolver_implements_classifier() {
        DependencyClassifier classifier = new DependencyResolver(CONFIG);

        assertThat(classifier.classify(URI.create("https://api.payments.internal/charge")))
                .isEqualTo("payment-service");
        assertThat(classifier.classifyHost("api.payments.internal")).isEqualTo("payment-service");
        assertThat(classifier.classify(URI.create("https://random.cloudfront.net/key")))
                .as("unmapped host falls back to the configured default name")
                .isEqualTo("unknown");
    }

    @Test
    void custom_classifier_can_take_path_into_account() {
        DependencyResolver fallback = new DependencyResolver(CONFIG);
        DependencyClassifier custom = new DependencyClassifier() {
            @Override
            public String classify(URI uri) {
                if (uri.getPath() != null && uri.getPath().startsWith("/api/v1/payments/")) {
                    return "payment-api-v1";
                }
                return fallback.classify(uri);
            }

            @Override
            public String classifyHost(String host) {
                return fallback.classifyHost(host);
            }
        };

        assertThat(custom.classify(URI.create("https://gateway.example.com/api/v1/payments/charge")))
                .as("path-aware classifier wins over the host-table default")
                .isEqualTo("payment-api-v1");
        assertThat(custom.classify(URI.create("https://api.payments.internal/charge")))
                .as("delegated calls still get the host-table behaviour")
                .isEqualTo("payment-service");
    }
}
