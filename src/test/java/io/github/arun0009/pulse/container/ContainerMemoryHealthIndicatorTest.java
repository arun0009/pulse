package io.github.arun0009.pulse.container;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerMemoryHealthIndicatorTest {

    private static final ContainerMemoryProperties CONFIG =
            new ContainerMemoryProperties(true, true, 0.10, "/sys/fs/cgroup");

    @Test
    void reportsUpWhenHeadroomAboveThreshold(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("cgroup.controllers"), "memory");
        Files.writeString(root.resolve("memory.current"), "104857600");
        Files.writeString(root.resolve("memory.max"), "536870912");

        ContainerMemoryMetrics metrics = registered(root);
        Health health = new ContainerMemoryHealthIndicator(metrics, CONFIG).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKeys("headroomRatio", "criticalRatio", "usedBytes", "limitBytes");
    }

    @Test
    void reportsOutOfServiceBelowThreshold(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("cgroup.controllers"), "memory");
        Files.writeString(root.resolve("memory.current"), "950000000");
        Files.writeString(root.resolve("memory.max"), "1000000000");

        ContainerMemoryMetrics metrics = registered(root);
        Health health = new ContainerMemoryHealthIndicator(metrics, CONFIG).health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    void reportsUnknownWhenNoCgroup(@TempDir Path root) {
        ContainerMemoryMetrics metrics = new ContainerMemoryMetrics(
                new CgroupMemoryReader(root.resolve("missing").toString()), new SimpleMeterRegistry());
        // register() returns false, no gauges; snapshot stays empty.
        Health health = new ContainerMemoryHealthIndicator(metrics, CONFIG).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
    }

    private ContainerMemoryMetrics registered(Path root) {
        ContainerMemoryMetrics metrics =
                new ContainerMemoryMetrics(new CgroupMemoryReader(root.toString()), new SimpleMeterRegistry());
        metrics.register();
        return metrics;
    }
}
