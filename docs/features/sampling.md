# Sampling

Tail sampling at the Collector is the right answer for production-scale
trace storage. *In-process* sampling is the right answer for cost on the
emitter side. Most Spring apps either ship 100% to a saturated Collector
or hand-roll a head sampler that drops the spans you care about most —
the error spans.

**Pulse gives you a clean ratio sampler with one knob**, plus a best-effort
*"rescue error spans the head sampler would have dropped"* pass.

## What you get

A single knob for cost:

```yaml
pulse:
  sampling:
    probability: 0.10   # ship 10% to the Collector
```

Plus an automatic upgrade for spans that turn out to be errors. When a span
finishes with `http.response.status_code >= 500`, an `exception.type`
attribute, or a non-OK gRPC status code, Pulse flips it to `RECORD_AND_SAMPLE`
even if the head sampler would have dropped it.

The result: you ship 10% of normal traffic *and* every error span. That's
the right cost / signal trade-off for most teams.

## Turn it on

On by default at 100% in dev. For prod, set the probability:

```yaml
pulse:
  sampling:
    probability: 0.10
```

`prefer-on-error` defaults to `true` and works automatically alongside the
ratio sampler.

## What it adds

| Knob | Default | Notes |
| --- | --- | --- |
| `probability` | `1.0` | Feeds the OTel `TraceIdRatioBased` sampler |
| `prefer-on-error` | `true` | Best-effort upgrade pass at span start; rescues error spans |

## Honest about limits

Pulse's `prefer-on-error` is an *in-process* upgrade — the decision is made
when the span starts, based on the information available at that moment.
True tail sampling (where the Collector decides after seeing the whole
trace) needs the OTel Collector. Pulse is the in-process layer; you still
want the Collector for the rest.

## When to skip it

If your Collector handles all sampling and you want every span to leave the
JVM:

```yaml
pulse:
  sampling:
    probability: 1.0
    prefer-on-error: false
```

---

**Source:** [`io.github.arun0009.pulse.guardrails`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/guardrails) ·
**Status:** Stable since 1.0.0
