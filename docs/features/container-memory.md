# Container-aware memory

> **TL;DR.** cgroup v1/v2 reader (no agent), real container memory metrics,
> OOM-kill counter, readiness health. The pod gets pulled out of rotation
> *before* the kernel kills it.

`Runtime.maxMemory()` lies inside containers. The JVM thinks it has the host's
RAM; the cgroup OOMKiller knows otherwise. The first sign of trouble is the
pod going `OOMKilled` while your dashboard happily reports 30% memory used.

**Pulse reads the actual container limit** and exposes the real headroom as
both a metric and a health indicator — so the readiness probe can pull the
pod out of rotation *before* the kernel does.

## What you get

```promql
pulse_container_memory_headroom_ratio < 0.10
```

Tune the threshold to match `pulse.container-memory.headroom-critical-ratio`
(default **0.10**). The headroom ratio drops below that level before the
OOMKiller typically fires, giving
the readiness probe time to drain the pod and the cluster time to spin up
a replacement. The shipped alert (`PulseContainerMemoryLow`) fires here.

You also get a true `oom_kills` counter — if the pod somehow survives an
oom-kill event (rare, but happens with cgroup v2 memory.events), you see it.

## Turn it on

Nothing. On by default. cgroup v1 and v2 are both detected automatically,
no JNI or agent.

## What it adds

| Metric | Type | Meaning |
| --- | --- | --- |
| `pulse.container.memory.used` | Gauge (bytes) | Actual container memory in use |
| `pulse.container.memory.limit` | Gauge (bytes) | The cgroup limit (the truth) |
| `pulse.container.memory.headroom_ratio` | Gauge (0.0–1.0) | `1 − used / limit` |
| `pulse.container.memory.oom_kills` | Counter | Increments on every cgroup oom_kill event |

A health indicator (`/actuator/health/containerMemory`) reports
**`OUT_OF_SERVICE`** when headroom falls below
`pulse.container-memory.headroom-critical-ratio` (default **10%**), so
Kubernetes can pull the pod from the load balancer before the kernel kills
it. On hosts without cgroup visibility (laptops, plain VMs) the indicator
returns **`UNKNOWN`** instead of failing readiness.

## When to skip it

If you're not running in a container, the metric values are meaningless.
Pulse detects this at startup and the indicator is a no-op, but you can
disable it explicitly:

```yaml
pulse:
  container-memory:
    enabled: false
```

---

**Source:** [`io.github.arun0009.pulse.container`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/container) ·
**Status:** Stable since 1.0.0
