# Container-aware memory

> **Status:** Stable · **Config prefix:** `pulse.container.memory` ·
> **Source:** [`io.github.arun0009.pulse.container`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/container)

## Value prop

`Runtime.maxMemory()` lies inside containers. The JVM thinks it has the
host's RAM; the cgroup OOMKiller knows otherwise. Pulse reads the actual
container memory limit and exposes headroom as a metric *and* a health
indicator, so the readiness probe can pull the pod out of rotation
**before** the OOMKiller does.

## What it does

`CgroupMemoryReader` parses cgroup v1 *and* v2 inside the JVM (no JNI, no
agent), exposing:

- `pulse.container.memory.used` (gauge, bytes)
- `pulse.container.memory.limit` (gauge, bytes)
- `pulse.container.memory.headroom_ratio` (gauge, 0.0–1.0)
- `pulse.container.memory.oom_kills` (counter — increments when memory.events
  reports an oom_kill)

`ContainerMemoryHealthIndicator` flips DEGRADED below
`pulse.container.memory.warning-headroom-ratio` (default 0.15) so a
Kubernetes readiness probe can drain the pod before it dies.

## Configuration

```yaml
pulse:
  container:
    memory:
      enabled: true
      health-indicator-enabled: true
      headroom-critical-ratio: 0.10
      cgroup-root: /sys/fs/cgroup
```

!!! note "Expanded coverage coming"

    Full reference (cgroup v1 vs v2 path resolution, oom-kill counter
    semantics, recommended Kubernetes probe configuration) lands in a
    1.0.x patch.
