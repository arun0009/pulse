# Trace-context guard

> **TL;DR.** `pulse.trace.received` vs `pulse.trace.missing` per route, plus
> a shipped alert. Find the upstream that's stripping `traceparent` instead
> of staring at half-empty Jaeger views.

Distributed traces silently lose context because *some* service in the chain
isn't passing the `traceparent` header along. Today you only notice when half
of a trace is missing in Jaeger and you have no idea which hop dropped it.

**Pulse turns this from a forensic exercise into a metric.** Every inbound
request is counted as either `received` or `missing`, broken down by route. A
single PromQL ratio tells you exactly which `(service, route)` is the source
of the leak.

## What you get

```promql
sum by (service, route) (rate(pulse_trace_missing_total[5m]))
  /
sum by (service, route) (rate(pulse_trace_received_total[5m])
                       + rate(pulse_trace_missing_total[5m]))
```

Any non-trivial result is a hop where the upstream forgot to install an
OpenTelemetry propagator, or a load balancer / proxy is stripping the header.
The shipped `PulseTraceContextMissing` alert fires at >5% missing over
10 minutes and tells you the offending route in the message.

## Turn it on

Nothing. It's on by default. Pulse looks for either a W3C `traceparent`
header or a B3 trace ID, depending on the propagator the OTel SDK is
configured with.

To skip the guard for synthetic monitoring traffic without disabling it
globally — see [Conditional features](conditional-features.md):

```yaml
pulse:
  trace-guard:
    enabled-when:
      header-not-equals:
        client-id: test-client-id
```

## What it adds

| Metric | Tags | Meaning |
| --- | --- | --- |
| `pulse.trace.received` | `route` | Inbound requests that carried trace context |
| `pulse.trace.missing` | `route` | Inbound requests with no trace context |

The `route` tag uses the matched route pattern (`/orders/{id}`, not
`/orders/12345`) so cardinality stays bounded even under id-bearing paths.

## When to skip it

Disable it entirely if you operate a strict ingress that already enforces
trace propagation (Envoy with mandatory `traceparent`, Istio with required
propagation, an internal API gateway that 4xxs requests without a trace ID):

```yaml
pulse:
  trace-guard:
    enabled: false
```

To turn it into an enforcement mechanism instead — Pulse 500s any request
that arrives without trace context:

```yaml
pulse:
  trace-guard:
    fail-on-missing: true
```

Most teams want the metric, not the enforcement.

## Under the hood

A filter runs near the start of the chain. For every request:

1. Looks for either `traceparent` (W3C) or `X-B3-TraceId` (B3 legacy),
   depending on which propagator the OTel SDK is using.
2. Increments `pulse.trace.received{route}` or `pulse.trace.missing{route}`.
3. Adds a `trace.context.received` / `trace.context.missing` event to the
   active span so you can spot the boundary in any trace UI.

The guard does not generate trace context if it's missing — the OTel SDK
does that downstream, exactly as it would without Pulse.

### All the knobs

```yaml
pulse:
  trace-guard:
    enabled: true                        # default
    fail-on-missing: false               # default
    exclude-path-prefixes:               # default
      - /actuator
      - /health
      - /metrics
    enabled-when: {}                     # since 1.1.0; empty = run for every request
```

| Key | Default | Notes |
| --- | --- | --- |
| `enabled` | `true` | Master switch |
| `fail-on-missing` | `false` | Throw a 500 instead of just incrementing the counter |
| `exclude-path-prefixes` | `/actuator, /health, /metrics` | Coarse, always-on path skip |
| `enabled-when` | empty | Per-request gate — see [Conditional features](conditional-features.md) |

---

**Source:** [`TraceGuardFilter.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/core/TraceGuardFilter.java) ·
**Runbook:** [Trace context missing](../runbooks/trace-context-missing.md) ·
**Status:** Stable since 1.0.0
