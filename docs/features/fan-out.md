# Request fan-out

> **TL;DR.** Counts distinct downstreams per inbound request and warns the
> trace when fan-out crosses your threshold. Catches chatty endpoints
> before they topple a downstream.

Some endpoints accidentally call thirty downstream services because of an
unhelpful loop or a chatty serialiser. The first you find out is when one of
those downstreams falls over and the noisy endpoint is the suspect.

**Pulse counts the distinct downstreams per inbound request**, so chatty
endpoints show up *before* they become chatty incidents.

## What you get

```promql
histogram_quantile(0.95,
  sum by (endpoint, le) (rate(pulse_request_fan_out_bucket[5m])))
```

The 95th-percentile fan-out per endpoint. An unexpected jump means a code
change just made a previously-tight endpoint chatty — usually before it
shows up as a downstream incident.

## Turn it on

Nothing. Enabled by default alongside the [dependency health
map](dependencies.md), which it shares the underlying outbound
classification with.

## What it adds

| Metric | Type | Tags | Meaning |
| --- | --- | --- | --- |
| `pulse.request.fan_out` | Distribution summary | `endpoint` | Distinct logical dependencies called during the request |

## When to skip it

```yaml
pulse:
  dependencies:
    fan-out:
      enabled: false
```

## Conditional gating

Fan-out shares `pulse.dependencies.enabled-when` with the per-call RED
metrics — one block, two consistent skips. See
[Dependencies → Conditional gating](dependencies.md#conditional-gating).

---

**Source:** [`io.github.arun0009.pulse.dependencies`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/dependencies) ·
**Status:** Stable since 1.0.0
