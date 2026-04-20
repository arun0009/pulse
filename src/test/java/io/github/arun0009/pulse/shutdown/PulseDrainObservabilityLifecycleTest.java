package io.github.arun0009.pulse.shutdown;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PulseDrainObservabilityLifecycleTest {

    @Test
    void noInflightRecordsZeroDuration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InflightRequestCounter counter = new InflightRequestCounter(registry);
        PulseDrainObservabilityLifecycle lifecycle = new PulseDrainObservabilityLifecycle(
                counter, new ShutdownProperties.Drain(true, Duration.ofSeconds(1)), registry);

        lifecycle.start();
        lifecycle.stop();

        assertThat(registry.get("pulse.shutdown.drain.duration").timer().count())
                .isOne();
        assertThat(registry.get("pulse.shutdown.dropped").counter().count()).isZero();
    }

    @Test
    void inflightAtDeadlineCountsAsDropped() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PulseDrainObservabilityLifecycle lifecycle = new PulseDrainObservabilityLifecycle(
                () -> 3, new ShutdownProperties.Drain(true, Duration.ofMillis(150)), registry);

        lifecycle.start();
        lifecycle.stop();

        assertThat(registry.get("pulse.shutdown.dropped").counter().count()).isEqualTo(3.0);
        assertThat(registry.get("pulse.shutdown.drain.duration").timer().count())
                .isOne();
    }
}
