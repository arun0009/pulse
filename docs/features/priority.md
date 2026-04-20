# Request priority

> **TL;DR.** `Pulse-Priority` header propagates end-to-end on MDC, baggage,
> and outbound calls. Your load shedders can drop the right requests when
> the system is full.

When the system is overloaded, *some* requests matter more than others —
checkout > recommendations, paid-tier > free, foreground > background.
Without a priority signal you load-shed indiscriminately and drop the
requests that pay the bills.

**Pulse propagates a `Pulse-Priority` header end-to-end** so your load
shedders can drop the right requests, and your alerts can ignore noisy
low-priority error bursts.

## What you get

Your shedding logic becomes a one-liner:

```java
if (RequestPriority.current().filter(p -> p == RequestPriority.LOW).isPresent()) {
    return Flux.empty();   // shed this request
}
```

Your error budget alerts can be scoped:

```promql
sum by (service) (rate(pulse_errors_unhandled_total{pulse_priority!="low"}[5m]))
```

Critical requests are also automatically escalated: when their timeout
budget is exhausted, the corresponding log line goes out at `WARN` instead
of `INFO`, so the on-call sees them faster.

## Turn it on

On by default with the five-tier vocabulary (`critical`, `high`, `normal`,
`low`, `background`). Callers just send `Pulse-Priority: critical` (or
whichever tier). The vocabulary is fixed by design — bounded cardinality is
the whole point — so Pulse won't accept arbitrary tier names.

To rename the inbound header, change the default tier when none is sent, or
opt into priority as a metric tag on a curated allow-list of meters:

```yaml
pulse:
  priority:
    header-name: Pulse-Priority      # default
    default-priority: normal         # default
    warn-on-critical-timeout-exhaustion: true   # default
    tag-meters: [http.server.requests]
```

## What it adds

| Where | Key |
| --- | --- |
| MDC | `priority` (Micrometer MDC key) |
| OTel baggage | `pulse.priority` |
| HTTP / Kafka outbound header | `Pulse-Priority` (configurable) |
| Thread-local accessor | `RequestPriority.current()` |

## When to skip it

If your platform already has a priority mechanism (Kubernetes
`PriorityClass`, Envoy priority routing, gRPC metadata you trust):

```yaml
pulse:
  priority:
    enabled: false
```

---

**Source:** [`io.github.arun0009.pulse.priority`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/priority) ·
**Status:** Stable since 1.0.0
