package io.github.arun0009.pulse.container;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerMemoryMetricsTest {

    @Test
    void registersGaugesWhenCgroupVisible(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("cgroup.controllers"), "memory");
        Files.writeString(root.resolve("memory.current"), "104857600");
        Files.writeString(root.resolve("memory.max"), "536870912");
        Files.writeString(root.resolve("memory.events"), "oom_kill 0\n");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ContainerMemoryMetrics metrics = new ContainerMemoryMetrics(new CgroupMemoryReader(root.toString()), registry);

        assertThat(metrics.register()).isTrue();

        assertThat(registry.get("pulse.container.memory.used").gauge().value()).isEqualTo(104_857_600.0);
        assertThat(registry.get("pulse.container.memory.limit").gauge().value()).isEqualTo(536_870_912.0);
        assertThat(registry.get("pulse.container.memory.headroom_ratio").gauge().value())
                .isCloseTo(0.8046875, org.assertj.core.api.Assertions.within(1e-6));
    }

    @Test
    void noOpWhenCgroupAbsent(@TempDir Path root) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ContainerMemoryMetrics metrics = new ContainerMemoryMetrics(
                new CgroupMemoryReader(root.resolve("missing").toString()), registry);

        assertThat(metrics.register()).isFalse();
        assertThat(registry.find("pulse.container.memory.used").gauge()).isNull();
    }
}
