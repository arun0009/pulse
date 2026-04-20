# Retry amplification

> **TL;DR.** `Pulse-Retry-Depth` baggage + a metric that fires *before* a
> three-deep retry chain becomes a 27× incident on the leaf service.

A three-deep retry chain with three retries at each level produces twenty-seven
attempts on the leaf service. By the time you see the cascade in your
dashboard, it's already in flight.

**Pulse counts retry depth as it crosses every hop**, so you can alert on a
storm forming *before* it becomes an incident.

## What you get

```promql
sum by (endpoint) (rate(pulse_retry_amplification_total[5m])) > 0
```

Any non-zero result is a request chain that's already retrying deeper than
your configured threshold — usually the leading symptom of a cascading
failure. The shipped alert (`PulseRetryAmplification`) fires here.

## Turn it on

Nothing. On by default with a depth threshold of 3.

To raise or lower the threshold:

```yaml
pulse:
  retry:
    amplification-threshold: 5
```

## What it adds

| Where | Key / metric | Meaning |
| --- | --- | --- |
| HTTP / Kafka header | `Pulse-Retry-Depth` | Current retry depth in the chain |
| OTel baggage | `pulse.retry.depth` | Same value, propagated to downstreams |
| Metric | `pulse.retry.amplification` (tag `endpoint`) | Calls that exceeded the threshold |
| Span event | `pulse.retry.amplification` | Marks the span where the threshold tripped |

Combined with [timeout-budget propagation](timeout-budget.md), these are the
two leading indicators of a cascade in flight.

## When to skip it

If your platform already enforces a retry-budget mechanism (Envoy retry
budgets, gRPC retry-policy with `max_attempts`):

```yaml
pulse:
  retry:
    enabled: false
```

---

**Source:** [`io.github.arun0009.pulse.resilience`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/resilience) ·
**Status:** Stable since 1.0.0
