package io.github.arun0009.pulse.container;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CgroupMemoryReaderTest {

    @Test
    void readsCgroupV2Layout(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("cgroup.controllers"), "memory cpu");
        Files.writeString(root.resolve("memory.current"), "104857600");
        Files.writeString(root.resolve("memory.max"), "536870912");
        Files.writeString(root.resolve("memory.events"), "low 0\nhigh 0\nmax 0\noom 1\noom_kill 2\n");

        CgroupMemoryReader.Snapshot snapshot = new CgroupMemoryReader(root.toString()).snapshot();

        assertThat(snapshot.used()).contains(104_857_600L);
        assertThat(snapshot.limit()).contains(536_870_912L);
        assertThat(snapshot.oomKillCount()).contains(2L);
        assertThat(snapshot.headroomRatio()).isCloseTo(0.8046875, org.assertj.core.api.Assertions.within(1e-6));
    }

    @Test
    void cgroupV2LimitMaxStringMeansUnbounded(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("cgroup.controllers"), "memory");
        Files.writeString(root.resolve("memory.current"), "10000");
        Files.writeString(root.resolve("memory.max"), "max");

        CgroupMemoryReader.Snapshot snapshot = new CgroupMemoryReader(root.toString()).snapshot();

        assertThat(snapshot.used()).contains(10_000L);
        assertThat(snapshot.limit()).isEmpty();
        assertThat(snapshot.headroomRatio()).isNull();
    }

    @Test
    void readsCgroupV1Layout(@TempDir Path root) throws IOException {
        Path memDir = Files.createDirectory(root.resolve("memory"));
        Files.writeString(memDir.resolve("memory.usage_in_bytes"), "1048576");
        Files.writeString(memDir.resolve("memory.limit_in_bytes"), "10485760");

        CgroupMemoryReader.Snapshot snapshot = new CgroupMemoryReader(root.toString()).snapshot();

        assertThat(snapshot.used()).contains(1_048_576L);
        assertThat(snapshot.limit()).contains(10_485_760L);
        assertThat(snapshot.headroomRatio()).isCloseTo(0.9, org.assertj.core.api.Assertions.within(1e-6));
    }

    @Test
    void cgroupV1UnsetLimitNormalizedToEmpty(@TempDir Path root) throws IOException {
        Path memDir = Files.createDirectory(root.resolve("memory"));
        Files.writeString(memDir.resolve("memory.usage_in_bytes"), "10000");
        Files.writeString(memDir.resolve("memory.limit_in_bytes"), Long.toString(CgroupMemoryReader.V1_UNSET));

        CgroupMemoryReader.Snapshot snapshot = new CgroupMemoryReader(root.toString()).snapshot();

        assertThat(snapshot.limit()).isEmpty();
    }

    @Test
    void missingCgroupRootReturnsEmptySnapshot(@TempDir Path root) {
        CgroupMemoryReader.Snapshot snapshot =
                new CgroupMemoryReader(root.resolve("does-not-exist").toString()).snapshot();

        assertThat(snapshot.used()).isEmpty();
        assertThat(snapshot.limit()).isEmpty();
        assertThat(snapshot.headroomRatio()).isNull();
    }
}
