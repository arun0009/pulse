package io.github.arun0009.pulse.actuator;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.github.arun0009.pulse.slo.SloRuleGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PulseEndpointTest {

    @Test
    void exposes_effective_config_and_runtime_segments() {
        PulseProperties props = bindEmpty();
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
    }

    @Test
    void slo_segment_returns_disabled_marker_when_generator_absent() {
        PulseProperties props = bindEmpty();
        PulseDiagnostics diagnostics = diagnostics(props);
        PulseEndpoint endpoint = new PulseEndpoint(diagnostics, null);

        assertThat(endpoint.read("slo")).asString().contains("pulse.slo.enabled=false");
    }

    @Test
    void config_hash_segment_returns_stable_hash_and_flat_entries() {
        PulseProperties props = bindEmpty();
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

    private static PulseProperties bindEmpty() {
        return new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("pulse", Bindable.of(PulseProperties.class));
    }

    private static PulseDiagnostics diagnostics(PulseProperties props) {
        return new PulseDiagnostics(props, "test-svc", "test-env", "0.0.1", null, null, null);
    }
}
