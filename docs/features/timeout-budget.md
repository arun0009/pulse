# Timeout-budget propagation

> **TL;DR.** The remaining deadline travels with the request across
> `RestTemplate`, `RestClient`, `WebClient`, `OkHttp`, Apache HttpClient 5,
> and Kafka. Doomed downstream calls fail fast instead of holding
> connections through the next retry storm.

The platform default timeout — 30 seconds, set once and forgotten — is what
every downstream service uses. The original caller may have already given up
after 2 seconds. The chain doesn't know, holds connections open, and feeds
the retry storm that takes the cluster down.

**Pulse propagates the deadline, not the timeout.** Each hop sees the real
remaining budget, fails fast when there's no time left, and never makes a
doomed call against a dying downstream.

## What you get

When the chain is healthy, the deadline shrinks at each hop:

```
Caller     ──POST /orders   Pulse-Timeout-Ms: 2000──▶  Edge
Edge       ──GET /stock     Pulse-Timeout-Ms: 1850──▶  Inventory     (300ms elapsed)
Edge       ──POST /charge   Pulse-Timeout-Ms: 1500──▶  Payment       (350ms elapsed)
```

When something is slow and the budget runs out, Pulse aborts the next call
*before* it goes out — and a single Prometheus query lights up:

```promql
sum by (transport) (rate(pulse_timeout_budget_exhausted_total[5m]))
```

This is the leading indicator of a cascading failure. With the shipped
`PulseTimeoutBudgetExhausted` alert, you see it minutes before the user does.

## Turn it on

Nothing. It's on by default with a 2 second budget per request.

To set a different default, or to forward a different header name to match an
existing convention:

```yaml
pulse:
  timeout-budget:
    default-budget: 5s              # used when no inbound header is present
    inbound-header: X-Deadline-Ms   # match your gateway's convention
```

To read the remaining budget from your own code:

```java
TimeoutBudget.current().ifPresent(budget -> {
    if (budget.remaining().toMillis() < 500) {
        // skip the optional enrichment call — not enough time
    }
});
```

## What it adds

| Where | Key | Value |
| --- | --- | --- |
| HTTP header (in / out) | `Pulse-Timeout-Ms` | Milliseconds remaining on the deadline |
| OTel baggage | `pulse.timeout.deadline_ms` | Absolute epoch-millis deadline |
| MDC (logs) | `timeout_remaining_ms` | Snapshot at log time |
| Metric | `pulse.timeout_budget.exhausted` (tag: `transport`) | Aborted outbound calls |

The metric is tagged by transport: `resttemplate`, `restclient`, `webclient`,
`okhttp`, `apache-hc5`, `kafka`. So you can see *which* client gave up.

## When to skip it

Disable when your platform already enforces a request budget end-to-end —
Envoy timeouts, Istio request timeouts, gRPC deadlines you trust — and you
don't want a parallel mechanism:

```yaml
pulse:
  timeout-budget:
    enabled: false
```

If you run an API gateway in front, configure it to set `Pulse-Timeout-Ms`
based on the gateway's own request timeout. Otherwise the first hop uses the
2-second default and only later hops see the propagated value.

## Conditional gating

To skip the budget filter for *some* requests (synthetic probes, internal
admin traffic) without disabling the feature, use the shared
[`enabled-when`](conditional-features.md) block:

```yaml
pulse:
  timeout-budget:
    enabled-when:
      header-not-equals:
        x-pulse-synthetic: "true"
      path-excludes:
        - /actuator
```

When the matcher rejects, no budget is established on baggage and
downstream calls see `TimeoutBudget.current() == Optional.empty()` —
your code already handles that.

## Under the hood

Three pieces work together:

1. A filter on the way in reads the deadline header (or applies the default),
   places it on a thread-local and on OTel baggage, and clears it in a
   `finally`.
2. An interceptor on the way out — wired into every supported HTTP and Kafka
   client — computes `remaining − safety-margin` and writes the outbound
   header.
3. If the remaining budget is below `minimum-budget` *before* the call fires,
   Pulse aborts the call, increments the exhausted counter, and emits a
   `WARN` log.

The header name follows RFC 6648 (no `X-` prefix). `inbound-header` and
`outbound-header` can be configured separately if you need to bridge between
two conventions.

---

**Source:** [`TimeoutBudget.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/guardrails/TimeoutBudget.java) ·
[`TimeoutBudgetFilter.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/guardrails/TimeoutBudgetFilter.java) ·
[`TimeoutBudgetOutboundInterceptor.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/guardrails/TimeoutBudgetOutboundInterceptor.java) ·
**Runbook:** [Timeout-budget exhausted](../runbooks/timeout-budget-exhausted.md) ·
**Status:** Stable since 1.0.0
