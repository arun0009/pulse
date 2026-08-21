# Timeout-budget propagation

> **TL;DR.** The remaining deadline travels with the request across
> `RestTemplate`, `RestClient`, `WebClient`, `OkHttp`, Apache HttpClient 5,
> and Kafka. Two clocks: an **RPC deadline** (the caller is waiting) and
> **event freshness** (how old is this Kafka record). They are not the
> same, and Pulse does not treat them as the same.

The platform default timeout — 30 seconds, set once and forgotten — is what
every downstream service uses. The original caller may have already given up
after 2 seconds. The chain doesn't know, holds connections open, and feeds
the retry storm that takes the cluster down.

**Pulse propagates the deadline, not the timeout.** Each hop reconstructs the
same absolute deadline (`Pulse-Timeout-Deadline-Ms`) and also stamps remaining
milliseconds for legacy hops. On HTTP, fail-fast and socket bounding are
**opt-in**. On Kafka, the consumer prefers the absolute header so a message
that sat in the topic does not get a fresh 2s budget. An explicit remaining of
`0` is expired — it is not "no header", and Pulse will not mint the 2s default.

## Two clocks

| Clock | What it answers | Default | How Pulse implements it |
| --- | --- | --- | --- |
| **RPC / request deadline** | The HTTP caller is still waiting. Should this hop even fire? | Observe only (stamp remaining + absolute deadline, still call) | `Pulse-Timeout-Deadline-Ms` (absolute epoch-millis) plus `Pulse-Timeout-Ms` (remaining) on HTTP and Kafka. Inbound prefers the absolute header. Opt-in: `abort-on-exhaustion`, `apply-client-timeout`. |
| **Event freshness** | Is this Kafka record too old to act on? | Off | `pulse.kafka.skip-stale-records` + `skip-stale-max-age`. Independent of the HTTP budget. |

A handler that returns `202 Accepted` and publishes "charge the card later"
must **not** drop that event because the original request's 2s budget
expired. Pulse never skips a Kafka listener just because the RPC deadline
passed. Enable skip-stale only when *your* business rule is "ignore events
older than N".

Kafka record timestamps are **not** used to reconstruct the RPC deadline.
They are often event-time (`order.placed_at`), not produce wall-clock.
The producer stamps `Pulse-Timeout-Deadline-Ms` from the in-process
`TimeoutBudget` instead. HTTP outbound interceptors stamp the same header so
a slow reverse proxy cannot restart remaining-ms on the next service.

## What you get

When the chain is healthy, the deadline shrinks at each hop:

```
Caller     ──POST /orders   Pulse-Timeout-Ms: 2000──▶  Edge
Edge       ──GET /stock     Pulse-Timeout-Ms: 1850──▶  Inventory     (300ms elapsed)
Edge       ──POST /charge   Pulse-Timeout-Ms: 1500──▶  Payment       (350ms elapsed)
```

When something is slow and the budget runs out, Pulse increments a counter
and still makes the call by default. A single Prometheus query lights up:

```promql
sum by (transport) (rate(pulse_timeout_budget_exhausted_total[5m]))
```

This is the leading indicator of a cascading failure. With the shipped
`PulseTimeoutBudgetExhausted` alert, you see it minutes before the user does.

## Turn it on

On by default with a 2 second budget per request. Outbound calls still
execute when the budget is exhausted; see the opt-in flags below.

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
| HTTP header (in / out) | `Pulse-Timeout-Ms` | Milliseconds remaining on the deadline. `0` means expired, not "use the default". |
| HTTP / Kafka header | `Pulse-Timeout-Deadline-Ms` | Absolute epoch-millis deadline (RPC clock). Preferred on inbound. |
| Kafka header (legacy + HTTP-shaped) | `Pulse-Timeout-Ms` | Remaining ms at produce time. Consume falls back to "remaining from now" only when the deadline header is absent. |
| OTel baggage | `pulse.timeout-budget.deadline.epoch.ms` | Absolute epoch-millis deadline |
| MDC (logs) | `timeout_remaining_ms` | Snapshot at log time |
| Metric | `pulse.timeout_budget.exhausted` (tag: `transport`) | Outbound calls made (or aborted) with remaining budget at or below `minimum-budget` |

The metric is tagged by transport: `resttemplate`, `restclient`, `webclient`,
`okhttp`, `apache-hc5`, `kafka`. So you can see *which* client gave up.

### Fail fast (skip the doomed call)

```yaml
pulse:
  timeout-budget:
    abort-on-exhaustion: true
```

When remaining budget is at or below `minimum-budget`, outbound **HTTP**
calls throw `TimeoutBudgetExhaustedException` instead of executing. Kafka
produces are never aborted — dropping a produce is not an observability
decision, and Kafka consume never skips because the HTTP deadline expired.

Enable abort only after you have confirmed the default 2s budget (or your
own) matches the service's real latency envelope. Combined with abort, a
2s default will fail slow legitimate endpoints.

### Bound the socket (the call cannot outlive the caller)

Stamping a header does not stop RestTemplate from waiting 30s. To actually
cap the in-flight call:

```yaml
pulse:
  timeout-budget:
    apply-client-timeout: true
```

| Client | What Pulse can do |
| --- | --- |
| OkHttp | Per-call connect / read / write timeout = remaining |
| WebClient | Reactor `.timeout(remaining)` on the exchange |
| Apache HttpClient 5 | Per-request `responseTimeout` = remaining |
| RestTemplate / RestClient | **Not supported** — no per-request timeout API. Use `abort-on-exhaustion`, or switch the factory to OkHttp / Apache. |

Do not turn this on in production while `default-budget` is the shipped 2s
unless 2s is your real p99 envelope. A healthy 800ms hop with 1.5s remaining
is fine; a 4s legitimate endpoint is not.

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

1. A filter on the way in prefers `Pulse-Timeout-Deadline-Ms`, else remaining-ms,
   else the default. An explicit `0` (or a past deadline) opens an *expired*
   budget — it does not fall back to 2s. `minimum-budget` floors only the
   implicit default, never an inbound header. `safety-margin` is subtracted
   when remaining-ms or the default establishes a new deadline; an absolute
   deadline is not taxed again.
2. An interceptor on the way out — wired into every supported HTTP and Kafka
   client — stamps remaining-ms *and* `Pulse-Timeout-Deadline-Ms`.
3. If the remaining budget is at or below `minimum-budget` *before* the
   call fires, Pulse increments `pulse.timeout_budget.exhausted` and
   stamps the remaining value (including `0`). The call still executes.
   Set `pulse.timeout-budget.abort-on-exhaustion=true` to throw
   `TimeoutBudgetExhaustedException` instead — HTTP only; Kafka produces
   are never aborted. Set `apply-client-timeout=true` so OkHttp / WebClient /
   Apache HttpClient 5 cannot wait longer than remaining. RestTemplate and
   RestClient still cannot set a per-request socket timeout.

The remaining-ms header name follows RFC 6648 (no `X-` prefix).
`inbound-header` and `outbound-header` can be configured separately if you
need to bridge between two conventions. The absolute deadline header name is
fixed.

Kafka event freshness (skip stale records) is documented with
[Kafka time-based lag](kafka-time-lag.md).

## Why this shape in 2026

There is still no IETF HTTP deadline header. gRPC sends a remaining timeout
and the server converts it to an absolute deadline on receipt — Pulse does
the same, then *forwards the absolute value* so a proxy cannot restart the
clock. OpenTelemetry baggage already carries the deadline in-process; the
headers are the hop that OTel does not own. In-process cardinality still
beats Collector views: the bill is incurred before the pipeline. Async
propagation stays on Micrometer `ContextSnapshot` (what Spring MVC / Boot 4
actually run); Java `ScopedValue` is not on that request path yet.

---

**Source:** [`TimeoutBudget.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/guardrails/TimeoutBudget.java) ·
[`TimeoutBudgetFilter.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/guardrails/TimeoutBudgetFilter.java) ·
[`TimeoutBudgetOutboundInterceptor.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/guardrails/TimeoutBudgetOutboundInterceptor.java) ·
**Runbook:** [Timeout-budget exhausted](../runbooks/timeout-budget-exhausted.md) ·
**Status:** Stable since 1.0.0
