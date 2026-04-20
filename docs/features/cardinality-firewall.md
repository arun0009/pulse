# Cardinality firewall

> **TL;DR.** Hard cap per `(meter, tag)`. The first 1 000 distinct values
> pass through; the rest bucket to `OVERFLOW`. One bad tag can't 10× your
> metrics bill. Default on, ~17 ns/op.

One mistakenly-tagged `userId` or `traceId` can register millions of unique
metrics overnight and 10× your Datadog/Honeycomb bill. The default Spring Boot
+ Micrometer stack will keep accepting new tag values forever — there is no
limit, no warning, no way to find out until finance asks why the invoice
quadrupled.

**Pulse caps the damage at the source.** Per `(metric, tag)`, the first 1000
distinct values pass through normally. Value 1001 onwards is renamed to
`OVERFLOW`. Your dashboards keep working. Your bill stops growing.

## What you get

One Prometheus query points at the runaway tag:

```promql
sum by (meter, tag_key) (rate(pulse_cardinality_overflow_total[5m]))
```

A single non-zero result is the feature working — Pulse just stopped a tag
from blowing up your bill. The query tells you exactly which metric and which
tag, so the fix takes minutes instead of an incident review.

You can also see it live without leaving curl:

```bash
$ curl -s localhost:8080/actuator/pulse/runtime | jq '.cardinalityFirewall.topOffenders'
[
  {
    "meter": "orders.placed",
    "tagKey": "userId",
    "overflowedValues": 14823,
    "distinctValuesSeen": 1000
  }
]
```

## Turn it on

Nothing. It's on by default with the right defaults. Add the Pulse starter
and you're done.

To raise or lower the cap:

```yaml
pulse:
  cardinality:
    max-tag-values-per-meter: 1000   # default
```

To opt a specific metric out (e.g. a business metric where you legitimately
want unbounded values):

```yaml
pulse:
  cardinality:
    exempt-meter-prefixes: [orders.placed.by-customer]
```

## What it adds

| Metric | Tags | Meaning |
| --- | --- | --- |
| `pulse.cardinality.overflow` | `meter`, `tag_key` | Count of tag values that got rewritten to `OVERFLOW`. Non-zero = the firewall just saved you. |

A pre-written alert (`PulseCardinalityOverflow`) ships with Pulse and fires
on the first overflow event, with the offending metric in the message.

## When to skip it

You almost never want to. The firewall does not throw, does not block, does
not affect normal-cardinality metrics, and costs ~17 ns per call on the hot
path. The only reason to disable it is if your metrics backend has its own
hard quota you trust more.

To turn it off entirely:

```yaml
pulse:
  cardinality:
    enabled: false
```

## Under the hood

Pulse registers a Micrometer `MeterFilter` on the global `MeterRegistry`.
Before any metric is recorded, the filter checks whether the tag value would
push that `(meter, tag)` over the cap. If so, the value is replaced with the
overflow string before it reaches the registry — so the metric family stays
intact, only the runaway value is bucketed.

A small per-`(meter, tag)` set tracks distinct values. Memory is bounded:
roughly `protected_meters × tag_keys × 1000 × 64 bytes`. Ten meters at five
tags each at saturation is about 3 MB.

The first overflow per `(meter, tag)` logs a one-line `WARN` so you don't
have to be looking at Prometheus to notice. Subsequent overflows are
silent — `rate(pulse_cardinality_overflow_total)` is the live signal.

---

**Source:** [`CardinalityFirewall.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/guardrails/CardinalityFirewall.java) ·
**Runbook:** [Cardinality firewall overflow](../runbooks/cardinality-firewall-overflow.md) ·
**Status:** Stable since 1.0.0
