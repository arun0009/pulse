# Trace-context guard

> **Status:** Stable · **Config prefix:** `pulse.trace-guard` ·
> **Source:** [`TraceGuardFilter.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/core/TraceGuardFilter.java) ·
> **Runbook:** [Trace context missing](../runbooks/trace-context-missing.md)

## Value prop

Distributed traces silently lose context because *some* service in the chain
doesn't propagate `traceparent`. Today you only notice when half of a trace
is missing in Jaeger and you have no idea which hop dropped it.

The guard turns this from a forensic exercise into a metric.

## What it does

`TraceGuardFilter` runs as a `OncePerRequestFilter` near the very start of
the chain. For every inbound request, it:

1. Looks for a W3C `traceparent` header (or B3 headers, depending on the
   configured propagator).
2. Increments either `pulse.trace.received{route}` or
   `pulse.trace.missing{route}` — the route tag uses the matched route
   pattern (`/orders/{id}`, not `/orders/12345`) so cardinality stays
   bounded.
3. Adds a `trace.context.received` or `trace.context.missing` event to the
   active span so you can spot the boundary in any trace UI.

There is no behavioural change to the request — the guard only observes.

## What this gives you

A single Prometheus query points directly at the upstream that's stripping
the header:

```promql
sum by (service, route) (rate(pulse_trace_missing_total[5m]))
  /
sum by (service, route) (rate(pulse_trace_received_total[5m]) + rate(pulse_trace_missing_total[5m]))
```

Any `(service, route)` with a non-trivial missing ratio is a hop where the
caller forgot to install an OTel propagator, or a load balancer / proxy is
dropping the header.

## Pulse never does this

The guard does **not**:

- Refuse the request — propagation is best-effort, not a hard requirement.
- Generate a trace context if one is missing — the OTel SDK does that
  downstream, exactly as it would without Pulse.
- Log per-request — that would be log spam at scale. Per-route counters are
  the right granularity.

## Metrics emitted

| Metric | Type | Tags | Description |
|---|---|---|---|
| `pulse.trace.received` | Counter | `route` | Inbound requests carrying a recognised trace header |
| `pulse.trace.missing` | Counter | `route` | Inbound requests with no recognised trace header |

The `route` tag uses Spring's matched route pattern. Unmatched requests
(404s, raw paths) are tagged `route="UNMATCHED"` to keep cardinality
predictable.

## Headers / baggage / MDC

The guard reads `traceparent` (W3C) and/or `b3` (B3 single-header) /
`X-B3-TraceId` (B3 multi-header) depending on the configured OTel
`TextMapPropagator`. It does not write anything itself — the OTel SDK
handles writing on the response.

## Configuration

```yaml
pulse:
  trace-guard:
    enabled: true                        # default
    span-event-on-received: true         # default
    span-event-on-missing: true          # default
    route-tag-fallback: UNMATCHED        # default
```

| Key | Type | Default | Notes |
|---|---|---|---|
| `enabled` | boolean | `true` | Master switch |
| `span-event-on-received` | boolean | `true` | Emit `trace.context.received` event on the span |
| `span-event-on-missing` | boolean | `true` | Emit `trace.context.missing` event on the span |
| `route-tag-fallback` | string | `UNMATCHED` | Tag value for requests with no matched route pattern |

## Shipped alert

`PulseTraceContextMissing` fires when the missing ratio for any
`(service, route)` exceeds 5% over 10 minutes. The runbook walks through
identifying the calling service from the dependency-map metrics.

## When to turn it off

Disable if you operate a strict ingress that already enforces propagation
(Envoy with `set_current_client_cert_details`, Istio with mandatory
propagation, an internal API gateway that 4xxs requests without
`traceparent`):

```yaml
pulse:
  trace-guard:
    enabled: false
```
