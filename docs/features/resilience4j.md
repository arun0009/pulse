# Resilience4j observability

> **TL;DR.** Auto-binds every breaker, retry, and bulkhead to Micrometer +
> span events. State transitions stop being invisible.

Resilience4j is what most Spring shops use for circuit breakers, retries, and
bulkheads. But its event consumers are silent unless you wire them up by
hand. The result: silent retries, invisible state transitions, and a
slow-trace investigation that goes nowhere because the breaker opened
twenty seconds ago and you never knew.

**Pulse subscribes to every Resilience4j registry automatically.** State
transitions, retry attempts, and bulkhead rejections become metrics and span
events with no per-app glue.

## What you get

When the breaker opens, you see it:

```promql
sum by (name) (rate(pulse_resilience_circuit_breaker_state_transitions_total
                      {to_state="open"}[5m]))
```

You also see it on the trace — every state transition becomes a span event,
so the slow-request investigation that used to dead-end at "the breaker is
open" now points directly at *when* and *why*.

## Turn it on

Nothing. Whenever a `CircuitBreakerRegistry`, `RetryRegistry`, or
`BulkheadRegistry` is on the classpath, Pulse subscribes to it — including
to registries created later at runtime.

## What it adds

| Metric | Tags |
| --- | --- |
| `pulse.resilience.circuit_breaker.state_transitions` | `name`, `from_state`, `to_state` |
| `pulse.resilience.circuit_breaker.state` | `name`, `state` |
| `pulse.resilience.circuit_breaker.errors` | `name`, `kind` |
| `pulse.resilience.retry.attempts` | `name`, `outcome` |
| `pulse.resilience.retry.exhausted` | `name` |
| `pulse.resilience.bulkhead.rejected` | `name` |

Plus a span event on the active span for every state transition and retry
attempt — eliminating the silent-retry blind spot.

## When to skip it

If you already export Resilience4j metrics through
`micrometer-registry-prometheus` and don't want duplicate series:

```yaml
pulse:
  resilience:
    enabled: false
```

---

**Source:** [`io.github.arun0009.pulse.resilience`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/resilience) ·
**Status:** Stable since 1.0.0
