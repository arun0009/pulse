# Retry amplification detection

> **Status:** Stable · **Config prefix:** `pulse.retry` ·
> **Source:** [`io.github.arun0009.pulse.propagation`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/propagation)

## Value prop

A 3-deep retry chain with `n=3` retries at each level produces 27 attempts
on the leaf service. By the time you notice, the cascade is already in
flight. Pulse propagates a `Pulse-Retry-Depth` baggage value through every
hop so you can alert on it *before* it becomes an incident.

## What it does

- Every outbound HTTP / Kafka call increments the propagated retry depth.
- When the depth crosses `pulse.retry.amplification-threshold` (default
  `3`), Pulse fires:
    - Counter `pulse.retry.amplification{endpoint}`
    - Span event `pulse.retry.amplification`
    - One-time `WARN` log per endpoint per minute
- Combined with [timeout-budget propagation](timeout-budget.md), these are
  the two leading indicators of a cascading failure in flight.

## Configuration

```yaml
pulse:
  retry:
    enabled: true
    amplification-threshold: 3
    baggage-key: pulse.retry.depth
    outbound-header: Pulse-Retry-Depth
```

!!! note "Expanded coverage coming"

    Full reference (propagator integration notes, amplification alert
    PromQL, runbook) lands in a 1.0.x patch.
