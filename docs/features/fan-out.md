# Request fan-out width

> **Status:** Stable · **Config prefix:** `pulse.dependencies` (shared with
> [dependency health map](dependencies.md)) ·
> **Source:** [`io.github.arun0009.pulse.dependencies`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/dependencies)

## Value prop

Some endpoints accidentally call 30 downstream services because of an
unhelpful loop or a chatty serialiser. The first you find out is when one
of those downstreams falls over. Pulse counts the distinct downstreams per
inbound request so you can alert on chatty endpoints *before* they become
chatty incidents.

## What it does

For every inbound request, Pulse counts the distinct logical dependencies
called during that request and records:

- `pulse.request.fan_out{endpoint}` — distribution summary

Combined with [dependency health map](dependencies.md), this gives you both
"how many downstreams" *and* "how each one is behaving."

## Configuration

```yaml
pulse:
  dependencies:
    fan-out:
      enabled: true
```

!!! note "Expanded coverage coming"

    Full reference (dependency-grouping rules, default histogram buckets,
    recommended alert thresholds) lands in a 1.0.x patch.
