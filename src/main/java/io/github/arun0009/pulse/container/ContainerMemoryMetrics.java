package io.github.arun0009.pulse.container;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Registers the {@code pulse.container.memory.*} family of meters using values polled from
 * {@link CgroupMemoryReader}. Polling on read (rather than on a schedule) keeps every value
 * perfectly fresh per scrape and removes the need for an extra thread.
 *
 * <p>If the underlying snapshot has no usable values (host JVM, macOS dev laptop, etc.) the
 * gauges silently report {@link Double#NaN} which Prometheus filters out — there is no
 * misleading "limit = 0" panel.
 *
 * <p>The OOM-kill counter is a {@link FunctionCounter} that wraps the cgroup's own monotonic
 * counter. Prometheus's {@code rate()} reflects real OOM-kill events without us having to
 * subscribe to kernel notifications, mirror values, or schedule a poller.
 *
 * <p>To amortize the file read across the (typically 4) meters in a single scrape, each call
 * caches the resulting snapshot for {@link #SNAPSHOT_TTL_MS} milliseconds. Scrapes happen
 * every 15-60s in practice, so the TTL is purely about coalescing the burst inside a single
 * scrape rather than reducing scrape-to-scrape work.
 */
public final class ContainerMemoryMetrics {

    private static final Logger log = LoggerFactory.getLogger(ContainerMemoryMetrics.class);
    private static final long SNAPSHOT_TTL_MS = 250L;

    private final CgroupMemoryReader reader;
    private final MeterRegistry registry;
    private final AtomicReference<CgroupMemoryReader.Snapshot> last =
            new AtomicReference<>(CgroupMemoryReader.Snapshot.empty());
    private volatile long lastReadAtMs;
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
        lastReadAtMs = System.currentTimeMillis();

        Gauge.builder(
                        "pulse.container.memory.used",
                        this,
                        m -> m.refresh().used().map(Long::doubleValue).orElse(Double.NaN))
                .description("Container memory in use as the kernel sees it (cgroup memory.current / usage_in_bytes)")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder(
                        "pulse.container.memory.limit",
                        this,
                        m -> m.refresh().limit().map(Long::doubleValue).orElse(Double.NaN))
                .description("Container memory hard limit (cgroup memory.max / limit_in_bytes)")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("pulse.container.memory.headroom_ratio", this, ContainerMemoryMetrics::headroomRatio)
                .description("1 - used/limit. Below 0.10 the kernel may OOM-kill.")
                .register(registry);

        FunctionCounter.builder("pulse.container.memory.oom_kills", this, m ->
                        (double) (long) m.refresh().oomKillCount().orElse(0L))
                .description("Cumulative OOM-kill events observed by the kernel for this cgroup hierarchy")
                .register(registry);

        wired = true;
        return true;
    }

    /**
     * Returns a fresh snapshot, re-reading from cgroup at most once every
     * {@link #SNAPSHOT_TTL_MS} ms so the burst of meter reads inside a single scrape
     * collapses to one file read.
     */
    private CgroupMemoryReader.Snapshot refresh() {
        long now = System.currentTimeMillis();
        if (now - lastReadAtMs >= SNAPSHOT_TTL_MS) {
            last.set(reader.snapshot());
            lastReadAtMs = now;
        }
        return last.get();
    }

    private double headroomRatio() {
        Double headroom = refresh().headroomRatio();
        return headroom == null ? Double.NaN : headroom;
    }

    /** Latest snapshot — used by the health indicator. */
    public CgroupMemoryReader.Snapshot snapshot() {
        return refresh();
    }
}
