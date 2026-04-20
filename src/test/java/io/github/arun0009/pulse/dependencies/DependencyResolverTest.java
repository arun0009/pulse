package io.github.arun0009.pulse.dependencies;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyResolverTest {

    @Test
    void exactHostMapsToLogicalName() {
        DependencyResolver resolver = build(Map.of("api.payments.internal", "payment-service"));
        assertThat(resolver.resolve(URI.create("https://api.payments.internal/charge")))
                .isEqualTo("payment-service");
    }

    @Test
    void hostMatchIsCaseInsensitive() {
        DependencyResolver resolver = build(Map.of("api.payments.internal", "payment-service"));
        assertThat(resolver.resolve(URI.create("https://API.PAYMENTS.INTERNAL/charge")))
                .isEqualTo("payment-service");
    }

    @Test
    void leadingDotEntryActsAsSuffixWildcard() {
        DependencyResolver resolver = build(Map.of(".payments.internal", "payment-service"));
        assertThat(resolver.resolve(URI.create("https://api.payments.internal/charge")))
                .isEqualTo("payment-service");
        assertThat(resolver.resolve(URI.create("https://grpc.payments.internal/charge")))
                .isEqualTo("payment-service");
    }

    @Test
    void unknownHostFallsBackToDefaultName() {
        DependencyResolver resolver = build(Map.of("api.payments.internal", "payment-service"));
        assertThat(resolver.resolve(URI.create("https://random.example.com/x"))).isEqualTo("unknown");
    }

    @Test
    void missingHostFallsBackToDefaultName() {
        DependencyResolver resolver = build(Map.of());
        assertThat(resolver.resolveHost("")).isEqualTo("unknown");
    }

    private static DependencyResolver build(Map<String, String> map) {
        return new DependencyResolver(new DependenciesProperties(
                true,
                map,
                "unknown",
                20,
                io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties.empty(),
                new DependenciesProperties.Health(true, java.util.List.of(), 0.05, false)));
    }
}
