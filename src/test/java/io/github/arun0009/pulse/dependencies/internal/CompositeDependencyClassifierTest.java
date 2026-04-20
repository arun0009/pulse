package io.github.arun0009.pulse.dependencies.internal;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import io.github.arun0009.pulse.dependencies.DependenciesProperties;
import io.github.arun0009.pulse.dependencies.DependencyClassifier;
import io.github.arun0009.pulse.dependencies.DependencyResolver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Pulse 2.0 chain-of-responsibility behavior of {@link DependencyClassifier}:
 * the composite walks every link in {@code @Order} sequence and the first non-null result wins,
 * with the host-table {@link DependencyResolver} acting as the terminal fallback.
 */
class CompositeDependencyClassifierTest {

    private static final DependenciesProperties CONFIG = new DependenciesProperties(
            true,
            Map.of("api.payments.internal", "payment-service"),
            "unknown",
            20,
            PulseRequestMatcherProperties.empty(),
            new DependenciesProperties.Health(true, List.of(), 0.05, false));

    @Nested
    class First_non_null_wins {

        @Test
        void path_aware_link_short_circuits_before_host_table() {
            DependencyClassifier pathAware = new PathClassifier("/api/v1/payments/", "payment-api-v1");
            DependencyClassifier hostTable = new DependencyResolver(CONFIG);

            CompositeDependencyClassifier composite = new CompositeDependencyClassifier(List.of(pathAware, hostTable));

            assertThat(composite.classify(URI.create("https://gateway.example.com/api/v1/payments/charge")))
                    .as("path-aware match wins; host-table is never consulted")
                    .isEqualTo("payment-api-v1");
        }

        @Test
        void path_aware_link_falls_through_to_host_table_when_path_does_not_match() {
            DependencyClassifier pathAware = new PathClassifier("/api/v1/payments/", "payment-api-v1");
            DependencyClassifier hostTable = new DependencyResolver(CONFIG);

            CompositeDependencyClassifier composite = new CompositeDependencyClassifier(List.of(pathAware, hostTable));

            assertThat(composite.classify(URI.create("https://api.payments.internal/charge")))
                    .as("first link returns null → second link's host-table match wins")
                    .isEqualTo("payment-service");
        }
    }

    @Nested
    class Terminal_default_name_guarantees_non_null {

        @Test
        void unmapped_host_yields_default_name_via_host_table_terminal() {
            DependencyClassifier pathAware = new PathClassifier("/never/matches/", "ignored");
            DependencyClassifier hostTable = new DependencyResolver(CONFIG);

            CompositeDependencyClassifier composite = new CompositeDependencyClassifier(List.of(pathAware, hostTable));

            assertThat(composite.classify(URI.create("https://random.cloudfront.net/key")))
                    .as("path-aware returns null and host-table terminal returns default-name")
                    .isEqualTo("unknown");
        }

        @Test
        void chain_returns_null_when_no_terminal_link_present() {
            // A misconfigured deployment (terminal link removed) is not a panic — the composite
            // surfaces null so the recorder's classify() helper can apply its own default-name
            // fallback. This guarantees the recorder still produces a tag.
            DependencyClassifier pathAware = new PathClassifier("/api/payments/", "payment");
            CompositeDependencyClassifier composite = new CompositeDependencyClassifier(List.of(pathAware));

            assertThat(composite.classify(URI.create("https://random.cloudfront.net/key")))
                    .as("no link matches and there is no terminal — composite returns null, recorder fills in")
                    .isNull();
        }
    }

    @Nested
    class Identity_dedup {

        @Test
        void same_instance_registered_twice_only_fires_once() {
            CountingClassifier link = new CountingClassifier();
            CompositeDependencyClassifier composite = new CompositeDependencyClassifier(List.of(link, link, link));

            composite.classify(URI.create("https://anything.example.com/path"));

            assertThat(link.invocations)
                    .as("identity dedup ensures the host-table resolver isn't invoked once per bean name")
                    .isEqualTo(1);
        }

        @Test
        void distinct_instances_with_equal_state_both_fire() {
            // Identity, not equality — two CountingClassifier instances are distinct objects
            // even though their state is identical, so both should run.
            CountingClassifier first = new CountingClassifier();
            CountingClassifier second = new CountingClassifier();
            CompositeDependencyClassifier composite = new CompositeDependencyClassifier(List.of(first, second));

            composite.classify(URI.create("https://anything.example.com/path"));

            assertThat(first.invocations).isEqualTo(1);
            assertThat(second.invocations).isEqualTo(1);
        }
    }

    @Nested
    class Host_string_path_mirrors_uri_path {

        @Test
        void classify_host_walks_chain_and_falls_back_to_default_name() {
            DependencyClassifier hostAware = new DependencyClassifier() {
                @Override
                public @Nullable String classify(URI uri) {
                    return null;
                }

                @Override
                public @Nullable String classifyHost(String host) {
                    return "specialhost".equals(host) ? "special-service" : null;
                }
            };
            DependencyClassifier hostTable = new DependencyResolver(CONFIG);

            CompositeDependencyClassifier composite = new CompositeDependencyClassifier(List.of(hostAware, hostTable));

            assertThat(composite.classifyHost("specialhost")).isEqualTo("special-service");
            assertThat(composite.classifyHost("api.payments.internal")).isEqualTo("payment-service");
            assertThat(composite.classifyHost("random.cloudfront.net")).isEqualTo("unknown");
        }
    }

    private static final class PathClassifier implements DependencyClassifier {
        private final String prefix;
        private final String tag;

        PathClassifier(String prefix, String tag) {
            this.prefix = prefix;
            this.tag = tag;
        }

        @Override
        public @Nullable String classify(URI uri) {
            return uri.getPath() != null && uri.getPath().startsWith(prefix) ? tag : null;
        }

        @Override
        public @Nullable String classifyHost(String host) {
            return null;
        }
    }

    private static final class CountingClassifier implements DependencyClassifier {
        int invocations;

        @Override
        public @Nullable String classify(URI uri) {
            invocations++;
            return null;
        }

        @Override
        public @Nullable String classifyHost(String host) {
            invocations++;
            return null;
        }
    }
}
