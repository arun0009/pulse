# Graceful drain + OTel flush

> **Status:** Stable · **Config prefix:** `pulse.shutdown` ·
> **Source:** [`io.github.arun0009.pulse.shutdown`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/shutdown)

## Value prop

Rolling deploys silently lose telemetry. The pod gets `SIGTERM`, the JVM
exits, and the last span batch never makes it to the Collector. By the
time you see the trace gap, the pod is gone. Pulse blocks JVM exit until
the OTel batch is drained, with a configurable timeout, and instruments
the readiness drain itself.

## What it does

- `pulse.shutdown.inflight` (gauge) — proves the readiness probe is
  draining (i.e., new requests stop, in-flight requests count down to 0).
- `pulse.shutdown.drain.duration` (timer) — how long the drain actually
  took.
- `pulse.shutdown.dropped` (counter) — requests still in flight when the
  drain window expires.
- `PulseDrainObservabilityLifecycle` runs as a `SmartLifecycle` just
  *before* the OTel flush, so the drain itself produces telemetry that
  reaches the Collector.
- The OTel `BatchSpanProcessor` flush blocks until either drained or
  `pulse.shutdown.otel-flush-timeout` (default `10s`) expires.

## Configuration

```yaml
pulse:
  shutdown:
    enabled: true
    otel-flush-timeout: 10s
    drain-timeout: 30s
```

!!! note "Expanded coverage coming"

    Full reference (interaction with Spring Boot's
    `lifecycle.timeout-per-shutdown-phase`, recommended Kubernetes
    `terminationGracePeriodSeconds`) lands in a 1.0.x patch.
