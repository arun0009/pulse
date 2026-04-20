# Sampling

> **TL;DR.** Pulse defers the head sampling rate to Spring Boot's standard
> `management.tracing.sampling.probability` and adds an additive
> *"keep error spans the head sampler would have dropped"* pass.

Tail sampling at the Collector is the right answer for production-scale
trace storage. *In-process* head sampling is the right answer for cost on
the emitter side. Most Spring apps either ship 100% to a saturated
Collector or hand-roll a head sampler that drops the spans you care about
most — the error spans.

**Pulse does not introduce a parallel knob for the head rate.** Spring
Boot already wires `management.tracing.sampling.probability` →
`ParentBased(TraceIdRatioBased(probability))`. Adding `pulse.sampling.probability`
on top would only invite drift between the value in your YAML and the
value the SDK actually applies.

What Pulse *does* add is a best-effort *"rescue error spans the head
sampler would have dropped"* pass.

## What you get

The standard Boot knob for cost:

```yaml
management:
  tracing:
    sampling:
      probability: 0.10   # ship 10% to the Collector
```

Plus Pulse's automatic upgrade for spans whose start-time attributes
already advertise an error (HTTP 5xx, an `exception.type`, an `error.type`,
or a non-OK gRPC status code). Such spans are flipped to
`RECORD_AND_SAMPLE` even if the head sampler would have dropped them:

```yaml
pulse:
  sampling:
    prefer-sampling-on-error: true   # default
```

The result: you ship 10% of normal traffic *and* every error span the head
sampler can already see. That's the right cost / signal trade-off for most
teams.

## How it works

Pulse registers a `BeanPostProcessor` that wraps whatever `Sampler` bean
is on the context — Spring Boot's default, your custom one, or the OTel
SDK fallback — with `PreferErrorSampler`. The wrapper is a pass-through
for non-error spans, so the configured probability still drives ordinary
traffic.

This means Pulse never publishes its own `Sampler` bean and never has to
race Boot for `@ConditionalOnMissingBean`.

## Turn it off

If your Collector handles all sampling and you want every span to leave
the JVM untouched:

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
pulse:
  sampling:
    prefer-sampling-on-error: false
```

## Honest about limits

Pulse's `prefer-sampling-on-error` is an *in-process* upgrade — the
decision is made when the span starts, based on the information available
at that moment. True tail sampling (where the Collector decides after
seeing the whole trace) needs the OTel Collector. Pulse is the in-process
layer; you still want the Collector for the rest.

## What it adds

| Knob | Default | Notes |
| --- | --- | --- |
| `management.tracing.sampling.probability` | `1.0` | Standard Spring Boot property; Pulse defers to it. Set per environment via the shipped `application-pulse-{env}.yml` presets. |
| `pulse.sampling.prefer-sampling-on-error` | `true` | Best-effort upgrade pass at span start; rescues error spans. |

---

**Source:** [`io.github.arun0009.pulse.guardrails`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/guardrails) ·
**Status:** Stable since 2.0.0
