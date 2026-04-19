# Cardinality firewall

> **Status:** Stable · **Config prefix:** `pulse.cardinality` ·
> **Source:** [`CardinalityFirewall.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/guardrails/CardinalityFirewall.java) ·
> **Runbook:** [Cardinality firewall overflow](../runbooks/cardinality-firewall-overflow.md)

## Value prop

A single mistakenly-tagged `userId`, `traceId`, or path-with-id can register
millions of unique time series in your metrics backend overnight and 10×
your bill. The default OTel + Micrometer stack will happily accept every
distinct tag value forever — there is no upper bound, no warning, and the
first time you find out is when finance asks why the Datadog invoice
quadrupled.

Pulse caps the blast radius at the source.

## What it does

`CardinalityFirewall` is a Micrometer `MeterFilter` registered on the global
`MeterRegistry`. It runs *before* a meter is registered, and:

1. For each meter, tracks the distinct values seen for each tag key in an
   in-memory set.
2. Once a `(meter, tag)` pair exceeds
   `pulse.cardinality.max-tag-values-per-meter` (default 1000), every further
   value is rewritten to the configurable `OVERFLOW` bucket.
3. The first overflow per `(meter, tag)` fires a one-time `WARN` log with the
   offending meter name and tag key — no log spam, but actionable.
4. Increments the diagnostic counter
   `pulse.cardinality.overflow{meter,tag_key}` so you can find runaway tags
   from Prometheus alone.

The original meter family is preserved — only the specific runaway tag value
is bucketed, so the rest of your instrumentation keeps working.

## Performance

JMH-measured on JDK 21, Apple M-series:

| Scenario | Cost |
|---|---|
| Cached value, hot path | **~17 ns/op** |
| New value under cap | ~80 ns/op |
| Overflow, bucketed | ~90 ns/op |

Reproduce with `make bench`. Source benchmark:
[`CardinalityFirewallBenchmark.java`](https://github.com/arun0009/pulse/blob/main/src/test/java/io/github/arun0009/pulse/bench/CardinalityFirewallBenchmark.java).

## Metrics emitted

| Metric | Type | Tags | Description |
|---|---|---|---|
| `pulse.cardinality.overflow` | Counter | `meter`, `tag_key` | Number of tag values rewritten to `OVERFLOW`, broken down by the offending meter name and tag key |

`pulse.cardinality.*` meters are themselves exempted from the firewall —
otherwise the overflow counter's own tags would be bucketed and the
diagnostic would defeat itself.

## Diagnostics

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

The runtime endpoint reports the top offenders by overflow count, plus the
total rewrite count, so you can confirm the firewall is working without
opening a metrics backend.

## Configuration

```yaml
pulse:
  cardinality:
    enabled: true                        # default
    max-tag-values-per-meter: 1000       # default
    overflow-value: OVERFLOW             # default
    meter-prefixes-to-protect: []        # empty = protect every meter
    exempt-meter-prefixes: []            # explicitly exempt high-cardinality business meters
```

| Key | Type | Default | Notes |
|---|---|---|---|
| `enabled` | boolean | `true` | Master switch |
| `max-tag-values-per-meter` | int | `1000` | Per-`(meter, tag)` cap before overflow |
| `overflow-value` | string | `OVERFLOW` | Replacement string for over-cap values |
| `meter-prefixes-to-protect` | list&lt;string&gt; | `[]` | If non-empty, *only* these prefixes are protected; everything else is left alone |
| `exempt-meter-prefixes` | list&lt;string&gt; | `[]` | Meter names starting with these prefixes are never bucketed |

## Memory bound

Worst-case footprint is approximately:

```
protected_meters × tag_keys_per_meter × maxTagValuesPerMeter × ~64 bytes/entry
```

With the defaults (1000 values/meter), ten protected meters with five tag
keys each at saturation costs roughly 3 MB.

For services with very large meter inventories or memory constraints, lower
`max-tag-values-per-meter` or use `meter-prefixes-to-protect` to opt only the
high-risk meters into protection.

## Shipped alert

`alerts/prometheus/pulse-slo-alerts.yaml` includes a `PulseCardinalityOverflow`
alert that fires when `rate(pulse_cardinality_overflow_total[5m]) > 0`. The
alert points at the offending meter and tag key in its annotation, so the
runbook → fix path is immediate.

## When to turn it off

You almost never want to. The firewall does not throw, does not block, and
does not affect normal-cardinality meters. The only reason to disable it is
if your metrics backend already has a hard quota you trust more.

To opt a single meter out:

```yaml
pulse:
  cardinality:
    exempt-meter-prefixes: [my.high-cardinality.business.metric]
```

To disable entirely:

```yaml
pulse:
  cardinality:
    enabled: false
```
