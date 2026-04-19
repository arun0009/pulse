# Sampling

> **Status:** Stable · **Config prefix:** `pulse.sampling` ·
> **Source:** [`io.github.arun0009.pulse.guardrails`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/guardrails)

## Value prop

Tail sampling at the Collector is the right answer for production-scale
trace storage. *In-process* sampling is the right answer for cost on the
emitter side. Pulse gives you a clean ratio sampler with one knob, plus a
best-effort "rescue error spans the head sampler would have dropped" pass.

## What it does

- **Ratio sampler** — `pulse.sampling.probability` (default `1.0` in dev,
  configurable in prod) feeds the OTel `TraceIdRatioBased` sampler.
- **Prefer-sampling-on-error** — best-effort upgrade pass at span start
  that flips a span's sampling decision to `RECORD_AND_SAMPLE` when:
    - `http.response.status_code >= 500`, or
    - `exception.type` is set, or
    - gRPC status code is non-OK
  This rescues error spans the `TraceIdRatioBased` sampler would otherwise
  drop. It is **honest about its limit**: real tail sampling needs the OTel
  Collector — this is the in-process layer on top.

## Configuration

```yaml
pulse:
  sampling:
    probability: 0.10                    # 10% in prod, 1.0 in dev
    prefer-on-error: true                # default
```

!!! note "Expanded coverage coming"

    Full reference (interaction with parent-based sampling, Collector tail
    sampling recipes, ParentBased composition) lands in a 1.0.x patch.
