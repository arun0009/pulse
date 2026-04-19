package io.github.arun0009.pulse.container;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads memory accounting from the Linux cgroup pseudo-files. Supports both v1 (the legacy
 * {@code memory.limit_in_bytes} / {@code memory.usage_in_bytes} layout used by older RHEL,
 * EKS pre-1.25, and most CI runners) and v2 (the unified hierarchy that ships on modern
 * Linux, k8s ≥ 1.25, GKE Autopilot, and ECS Fargate Linux 5.10+).
 *
 * <p>Returns empty values on macOS, Windows, and any non-cgroup host so the surrounding gauges
 * silently no-op and the same starter works in dev and in containers without configuration.
 *
 * <p>"Limit unset" is normalized to {@link Optional#empty()} so the headroom calculation can't
 * divide by infinity. The cgroup convention for "no limit" is the absurdly large number
 * {@code 9223372036854771712} (v1) or the literal string {@code max} (v2); both map to empty.
 */
public final class CgroupMemoryReader {

    /** Sentinel limit cgroup v1 emits when no memory limit is set. */
    public static final long V1_UNSET = 9_223_372_036_854_771_712L;

    private final Path cgroupRoot;

    public CgroupMemoryReader(String cgroupRoot) {
        this.cgroupRoot = Path.of(cgroupRoot);
    }

    /** Snapshot of the live cgroup memory accounting. */
    public Snapshot snapshot() {
        if (!Files.isDirectory(cgroupRoot)) {
            return Snapshot.empty();
        }
        boolean v2 = Files.exists(cgroupRoot.resolve("cgroup.controllers"));
        if (v2) {
            return readV2();
        }
        return readV1();
    }

    private Snapshot readV2() {
        Optional<Long> used = readLong(cgroupRoot.resolve("memory.current"));
        Optional<Long> limit = readMaxOrLong(cgroupRoot.resolve("memory.max"));
        Optional<Long> oomKills = readKeyValue(cgroupRoot.resolve("memory.events"), "oom_kill");
        return new Snapshot(used, limit, oomKills);
    }

    private Snapshot readV1() {
        Optional<Long> used = readLong(cgroupRoot.resolve("memory/memory.usage_in_bytes"))
                .or(() -> readLong(cgroupRoot.resolve("memory.usage_in_bytes")));
        Optional<Long> limit = readLong(cgroupRoot.resolve("memory/memory.limit_in_bytes"))
                .or(() -> readLong(cgroupRoot.resolve("memory.limit_in_bytes")))
                .filter(value -> value != V1_UNSET);
        Optional<Long> oomKills = readKeyValue(cgroupRoot.resolve("memory/memory.oom_control"), "oom_kill")
                .or(() -> readKeyValue(cgroupRoot.resolve("memory.oom_control"), "oom_kill"));
        return new Snapshot(used, limit, oomKills);
    }

    private static Optional<Long> readLong(Path path) {
        try {
            if (!Files.isReadable(path)) return Optional.empty();
            String text = Files.readString(path).trim();
            return Optional.of(Long.parseLong(text));
        } catch (IOException | NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Long> readMaxOrLong(Path path) {
        try {
            if (!Files.isReadable(path)) return Optional.empty();
            String text = Files.readString(path).trim();
            if ("max".equalsIgnoreCase(text)) return Optional.empty();
            return Optional.of(Long.parseLong(text));
        } catch (IOException | NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Long> readKeyValue(Path path, String key) {
        try {
            if (!Files.isReadable(path)) return Optional.empty();
            for (String line : Files.readAllLines(path)) {
                if (line.startsWith(key + " ") || line.startsWith(key + "\t")) {
                    String value = line.substring(key.length()).trim();
                    return Optional.of(Long.parseLong(value));
                }
            }
            return Optional.empty();
        } catch (IOException | NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Memory readout. {@code limit} is empty when the cgroup is unbounded (host JVM, dev
     * laptop, no controller). {@code used} is empty when no cgroup accounting is available
     * — gauges should be silently skipped in that case.
     */
    public record Snapshot(Optional<Long> used, Optional<Long> limit, Optional<Long> oomKillCount) {
        public static Snapshot empty() {
            return new Snapshot(Optional.empty(), Optional.empty(), Optional.empty());
        }

        /**
         * Headroom as a ratio of the limit. {@code 1.0} = full headroom, {@code 0.0} = at the
         * limit. Returns {@code null} when either accounting is missing — the caller should
         * skip emitting the gauge rather than report a misleading {@code 1.0}.
         */
        public @Nullable Double headroomRatio() {
            if (used.isEmpty() || limit.isEmpty() || limit.get() <= 0) return null;
            double ratio = 1.0 - ((double) used.get() / (double) limit.get());
            return Math.max(ratio, 0.0);
        }
    }
}
