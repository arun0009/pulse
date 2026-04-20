package io.github.arun0009.pulse.container;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports {@code OUT_OF_SERVICE} when container memory headroom drops below
 * {@link ContainerMemoryProperties#headroomCriticalRatio()} (default 10%).
 * On hosts without cgroup accounting visible (macOS, Windows, host JVM) the indicator
 * silently reports {@code UNKNOWN} — preferable to {@code DOWN}, which would force-fail
 * readiness probes during local development.
 *
 * <p>When wired into a Kubernetes readiness probe, this lets the kubelet shift load away
 * from a pod that is one allocation away from an OOM-kill, instead of letting the kernel
 * decide which RPC dies in flight.
 */
public final class ContainerMemoryHealthIndicator implements HealthIndicator {

    private final ContainerMemoryMetrics metrics;
    private final double criticalRatio;

    public ContainerMemoryHealthIndicator(ContainerMemoryMetrics metrics, ContainerMemoryProperties config) {
        this.metrics = metrics;
        this.criticalRatio = config.headroomCriticalRatio();
    }

    @Override
    public Health health() {
        CgroupMemoryReader.Snapshot snapshot = metrics.snapshot();
        Double headroom = snapshot.headroomRatio();
        if (headroom == null) {
            return Health.unknown()
                    .withDetail("reason", "no cgroup memory accounting visible")
                    .build();
        }
        Health.Builder builder = (headroom < criticalRatio ? Health.outOfService() : Health.up())
                .withDetail("headroomRatio", headroom)
                .withDetail("criticalRatio", criticalRatio);
        snapshot.used().ifPresent(value -> builder.withDetail("usedBytes", value));
        snapshot.limit().ifPresent(value -> builder.withDetail("limitBytes", value));
        snapshot.oomKillCount().ifPresent(value -> builder.withDetail("oomKills", value));
        return builder.build();
    }
}
