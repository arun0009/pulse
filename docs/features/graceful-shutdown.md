# Graceful drain + OTel flush

> **TL;DR.** Blocks JVM exit until the OTel batch is drained, with a
> deadline. The last batch of spans before SIGTERM actually arrives.

Rolling deploys silently lose telemetry. The pod gets `SIGTERM`, the JVM
exits, and the last batch of spans never makes it to the Collector. By the
time you notice the trace gap, the pod is gone — you can't redeploy it to
recover the data.

**Pulse blocks JVM exit until the OTel batch is drained**, with a
configurable timeout, and instruments the drain itself so the drain
*also* makes it to the Collector.

## What you get

The drain is observable, end-to-end:

| Metric | Meaning |
| --- | --- |
| `pulse.shutdown.inflight` (gauge) | Proves the readiness probe is draining (in-flight count goes to 0) |
| `pulse.shutdown.drain.duration` (timer) | How long each drain took |
| `pulse.shutdown.dropped` (counter) | Requests still in flight when the drain window expired |

If `pulse.shutdown.dropped` is non-zero, your
`terminationGracePeriodSeconds` is too short or your drain is hanging.
Either way it's now a signal, not a mystery.

## Turn it on

Nothing. On by default. Defaults are conservative — 10 seconds for the OTel
flush, 30 seconds for the request drain.

To tune:

```yaml
pulse:
  shutdown:
    otel-flush-enabled: true   # default
    otel-flush-timeout: 15s
    drain:
      enabled: true            # default
      timeout: 60s
```

## Recommended Kubernetes config

For Pulse's drain to fit cleanly inside the pod lifecycle, set
`terminationGracePeriodSeconds` to comfortably exceed
`drain.timeout + otel-flush-timeout`. With Pulse defaults, **60 seconds** is
a safe value.

## When to skip it

If you already have a sidecar (Envoy, Istio) handling drain and an OTel
Collector running locally on the pod that you trust to flush, opt out
per-feature — there is no single global `pulse.shutdown.enabled` switch:

```yaml
pulse:
  shutdown:
    otel-flush-enabled: false
    drain:
      enabled: false
```

---

**Source:** [`io.github.arun0009.pulse.shutdown`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/shutdown) ·
**Status:** Stable since 1.0.0
