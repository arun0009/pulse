package io.github.arun0009.pulse.fleet;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigHashGaugeTest {

    @Test
    void registersConstantOneGaugeWithHashTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConfigHashGauge gauge = new ConfigHashGauge(registry, "abcd1234");
        gauge.register();

        Gauge meter = registry.get("pulse.config.hash").tag("hash", "abcd1234").gauge();
        assertThat(meter.value()).isEqualTo(1.0);
    }
}
