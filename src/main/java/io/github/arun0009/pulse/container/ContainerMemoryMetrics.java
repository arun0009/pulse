package io.github.arun0009.pulse.container;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registers the {@code pulse.container.memory.*} family of meters using values polled from
 * {@link CgroupMemoryReader} on every gauge read. Polling on read (rather than on a schedule)
 * keeps the gauge value perfectly fresh and removes the need for an extra thread.
 *
 * <p>If the underlying snapshot has no usable values (host JVM, macOS dev laptop, etc.) the
 * gauges silently report {@link Double#NaN} which Prometheus filters out — there is no
 * misleading "limit = 0" panel.
 *
 * <p>The OOM-kill counter is implemented as a {@link Counter} whose value is mirrored from the
 * cgroup's monotonic counter so a Prometheus {@code rate()} reflects real OOM-kill events
 * without us having to subscribe to kernel notifications.
 */
public final class ContainerMemoryMetrics {

    private static final Logger log = LoggerFactory.getLogger(ContainerMemoryMetrics.class);

    private final CgroupMemoryReader reader;
    private final MeterRegistry registry;
    private final AtomicReference<CgroupMemoryReader.Snapshot> last =
            new AtomicReference<>(CgroupMemoryReader.Snapshot.empty());
    private final AtomicLong oomMirror = new AtomicLong();
    private boolean wired;

    public ContainerMemoryMetrics(CgroupMemoryReader reader, MeterRegistry registry) {
        this.reader = reader;
        this.registry = registry;
    }

    /**
     * Idempotent — registers the gauges once, on the first invocation. Returns {@code true}
     * when the host actually has cgroup accounting (so meters were registered) and
     * {@code false} otherwise.
     */
    public synchronized boolean register() {
        if (wired) return true;
        CgroupMemoryReader.Snapshot snapshot = reader.snapshot();
        if (snapshot.used().isEmpty() && snapshot.limit().isEmpty()) {
            log.debug("Pulse container-memory: no cgroup accounting visible, skipping registration");
            return false;
        }
        last.set(snapshot);

        Gauge.builder("pulse.container.memory.used", this, ContainerMemoryMetrics::usedBytes)
                .description("Container memory in use as the kernel sees it (cgroup memory.current / usage_in_bytes)")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("pulse.container.memory.limit", this, ContainerMemoryMetrics::limitBytes)
                .description("Container memory hard limit (cgroup memory.max / limit_in_bytes)")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("pulse.container.memory.headroom_ratio", this, ContainerMemoryMetrics::headroomRatio)
                .description("1 - used/limit. Below 0.10 the kernel may OOM-kill.")
                .register(registry);

        Counter oomCounter = Counter.builder("pulse.container.memory.oom_kills")
                .description("Number of OOM-kill events observed in this cgroup hierarchy")
                .register(registry);

        Gauge.builder("pulse.container.memory.refresh_age", this, m -> 0.0)
                .description("Always 0 — gauges read live from cgroup on every scrape.")
                .baseUnit("seconds")
                .register(registry);

        // Bootstrap the OOM mirror to whatever the kernel reports right now so the first
        // poll afterwards correctly reflects deltas.
        snapshot.oomKillCount().ifPresent(oomMirror::set);
        wireOomCounter(oomCounter);
        wired = true;
        return true;
    }

    private void wireOomCounter(Counter counter) {
        // Mirror the cgroup's monotonic kill counter into the Micrometer counter on every
        // gauge poll. Cheap: one file read per scrape (typically 15-60s).
        Gauge.builder("pulse.container.memory.oom_kills_observed", this, m -> {
                    CgroupMemoryReader.Snapshot fresh = reader.snapshot();
                    last.set(fresh);
                    fresh.oomKillCount().ifPresent(value -> {
                        long delta = value - oomMirror.getAndSet(value);
                        if (delta > 0) counter.increment(delta);
                    });
                    return (double) (long) fresh.oomKillCount().orElse(0L);
                })
                .description("Cumulative cgroup oom_kill counter as last sampled.")
                .register(registry);
    }

    private double usedBytes() {
        return last.get().used().map(Long::doubleValue).orElse(Double.NaN);
    }

    private double limitBytes() {
        return last.get().limit().map(Long::doubleValue).orElse(Double.NaN);
    }

    private double headroomRatio() {
        Double headroom = last.get().headroomRatio();
        return headroom == null ? Double.NaN : headroom;
    }

    /** Latest snapshot — used by the health indicator. */
    public CgroupMemoryReader.Snapshot snapshot() {
        return last.get();
    }
}
