package io.github.arun0009.pulse.actuator;

import io.github.arun0009.pulse.logging.ResourceAttributeResolver;
import io.github.arun0009.pulse.slo.SloRuleGenerator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PulseEndpointTest {

    @Test
    void exposes_effective_config_and_runtime_segments() {
        PulseDiagnostics.AllProperties props = TestAllProperties.bindEmpty();
        PulseDiagnostics diagnostics = diagnostics(props);
        PulseEndpoint endpoint = new PulseEndpoint(diagnostics, new SloRuleGenerator(props.slo(), "test-svc"));

        Object effectiveConfig = endpoint.read("effective-config");
        Object runtime = endpoint.read("runtime");

        @SuppressWarnings("unchecked")
        Map<String, Object> effectiveConfigMap = (Map<String, Object>) effectiveConfig;
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeMap = (Map<String, Object>) runtime;

        assertThat(effectiveConfig).isInstanceOf(Map.class);
        assertThat(runtime).isInstanceOf(Map.class);
        assertThat(effectiveConfigMap).containsKey("pulse");
        assertThat(runtimeMap).containsKey("cardinalityFirewall");
        assertThat(runtimeMap).containsKey("resourceAttributes");
    }

    @Test
    void runtime_resource_attributes_unwired_when_no_resolver_bean() {
        PulseDiagnostics diagnostics = diagnostics(TestAllProperties.bindEmpty());
        PulseEndpoint endpoint = new PulseEndpoint(diagnostics, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) endpoint.read("runtime");
        @SuppressWarnings("unchecked")
        Map<String, Object> ra = (Map<String, Object>) runtime.get("resourceAttributes");
        assertThat(ra.get("wired")).isEqualTo(false);
    }

    @Test
    void runtime_resource_attributes_include_resolve_all_when_resolver_wired() {
        // Subclass so host.name is deterministic — stock resolver prefers OTEL_RESOURCE_ATTRIBUTES
        // and HOSTNAME/COMPUTERNAME over HostNameProvider.
        ResourceAttributeResolver resolver = new ResourceAttributeResolver(() -> "ignored") {
            @Override
            protected String hostName() {
                return "diag-test-host";
            }
        };
        PulseDiagnostics.AllProperties props = TestAllProperties.bindEmpty();
        PulseDiagnostics diag =
                new PulseDiagnostics(props, "test-svc", "test-env", "0.0.1", 1.0, null, null, null, null, resolver);
        PulseEndpoint endpoint = new PulseEndpoint(diag, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) endpoint.read("runtime");
        @SuppressWarnings("unchecked")
        Map<String, Object> ra = (Map<String, Object>) runtime.get("resourceAttributes");
        assertThat(ra.get("wired")).isEqualTo(true);
        assertThat(ra.get("resolverClass")).isEqualTo(resolver.getClass().getName());
        @SuppressWarnings("unchecked")
        Map<String, String> resolved = (Map<String, String>) ra.get("resolved");
        assertThat(resolved.get("host.name")).isEqualTo("diag-test-host");
    }

    @Test
    void slo_segment_returns_disabled_marker_when_generator_absent() {
        PulseDiagnostics.AllProperties props = TestAllProperties.bindEmpty();
        PulseDiagnostics diagnostics = diagnostics(props);
        PulseEndpoint endpoint = new PulseEndpoint(diagnostics, null);

        assertThat(endpoint.read("slo")).asString().contains("pulse.slo.enabled=false");
    }

    @Test
    void config_hash_segment_returns_stable_hash_and_flat_entries() {
        PulseDiagnostics.AllProperties props = TestAllProperties.bindEmpty();
        PulseDiagnostics diagnostics = diagnostics(props);
        PulseEndpoint endpoint = new PulseEndpoint(diagnostics, new SloRuleGenerator(props.slo(), "test-svc"));

        Object first = endpoint.read("config-hash");
        Object second = endpoint.read("config-hash");

        @SuppressWarnings("unchecked")
        Map<String, Object> firstMap = (Map<String, Object>) first;
        @SuppressWarnings("unchecked")
        Map<String, Object> secondMap = (Map<String, Object>) second;
        assertThat(firstMap).containsKeys("hash", "entries");
        assertThat(firstMap.get("hash")).isEqualTo(secondMap.get("hash"));
    }

    private static PulseDiagnostics diagnostics(PulseDiagnostics.AllProperties props) {
        return new PulseDiagnostics(props, "test-svc", "test-env", "0.0.1", 1.0, null, null, null, null, null);
    }
}
