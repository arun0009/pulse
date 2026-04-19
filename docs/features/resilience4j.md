# Resilience4j auto-instrumentation

> **Status:** Stable · **Config prefix:** `pulse.resilience` ·
> **Source:** [`io.github.arun0009.pulse.resilience`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/resilience)

## Value prop

Resilience4j is what most Spring shops use for circuit breakers, retries,
and bulkheads, but its event consumers are silent unless you wire them up
yourself. The result: silent retries, invisible state transitions, and a
slow-trace investigation that goes nowhere because the `CircuitBreaker`
opened twenty seconds ago and you never knew.

Pulse auto-attaches event consumers to every Resilience4j registry. You do
nothing.

## What it does

When a `CircuitBreakerRegistry` / `RetryRegistry` / `BulkheadRegistry` is
on the classpath, Pulse:

- Subscribes to event consumers on every registry, including ones added
  later.
- Records the events as Micrometer metrics:
    - `pulse.resilience.circuit_breaker.{state_transitions,state,errors}`
    - `pulse.resilience.retry.{attempts,exhausted}`
    - `pulse.resilience.bulkhead.rejected`
- Adds span events to the active span when state transitions and retry
  attempts happen — eliminating the silent-retry blind spot.

## Configuration

```yaml
pulse:
  resilience:
    enabled: true
    span-events-enabled: true
```

!!! note "Expanded coverage coming"

    Full reference (registry detection rules, event-payload mapping, sample
    Grafana panel) lands in a 1.0.x patch.
