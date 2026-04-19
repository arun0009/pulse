# Request priority

> **Status:** Stable · **Config prefix:** `pulse.priority` ·
> **Source:** [`io.github.arun0009.pulse.priority`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/priority)

## Value prop

When the system is overloaded, *some* requests matter more than others —
checkout > recommendations, paid-tier > free, foreground > background.
Pulse propagates a `Pulse-Priority` header end-to-end so user-code load
shedders can drop low-priority work and your alerts can ignore noisy
low-priority error bursts.

## What it does

The `RequestPriorityFilter` reads the configured header (default
`Pulse-Priority`) and resolves it to one of the configured tiers
(default: `critical`, `high`, `normal`, `low`). The resolved priority lands
on:

- **MDC** (`pulse.priority`)
- **OTel baggage** (key `pulse.priority`)
- **Outbound HTTP / Kafka headers** (configurable name)
- **`RequestPriority.current()`** — a thread-local accessor your code reads
  directly without touching the OTel API

Critical requests are also escalated automatically: when their timeout
budget is exhausted, the corresponding log line is emitted at `WARN`
instead of `INFO`, so error-budget alerts surface them faster.

## Reading from your code

```java
if (RequestPriority.current().filter(p -> p == RequestPriority.LOW).isPresent()) {
    return Flux.empty();   // shed this request
}
```

## Configuration

```yaml
pulse:
  priority:
    enabled: true
    inbound-header: Pulse-Priority
    default-priority: normal
    propagate-on-baggage: true
    custom-tiers: []
```

!!! note "Expanded coverage coming"

    Full reference (custom tier ordering, alert wiring, integration with
    the [timeout-budget](timeout-budget.md) WARN escalation) lands in a
    1.0.x patch.
