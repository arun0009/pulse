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
        PulseDiagnostics diagnostics = new PulseDiagnostics(props, "test-svc", "test-env", "0.0.1", null);
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

    private static PulseProperties bindEmpty() {
        return new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("pulse", Bindable.of(PulseProperties.class));
    }
}
