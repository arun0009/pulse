# OpenFeature correlation

> **Status:** Stable · **Config prefix:** `pulse.open-feature` ·
> **Source:** [`PulseOpenFeatureMdcHook.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/openfeature/PulseOpenFeatureMdcHook.java)

## Value prop

Feature flags are the most under-instrumented part of most production
systems. *"Did the bug only affect users with the new checkout flow on?"*
should be a one-line query — but only if every flag evaluation is
correlated with the trace and the log line.

## What it does

When `dev.openfeature:sdk` is on the classpath, Pulse auto-registers a hook
that stamps every flag evaluation on:

- **MDC** — `feature_flag.key`, `feature_flag.variant`,
  `feature_flag.provider_name`
- **Active span** as an OTel-semconv `feature_flag` event

If the optional `dev.openfeature.contrib.hooks:otel` hook is also present,
Pulse registers it reflectively so consumers do not have to wire it
themselves.

## Configuration

```yaml
pulse:
  open-feature:
    enabled: true
```

Setting `pulse.open-feature.enabled=false` suppresses both registrations.

!!! note "Expanded coverage coming"

    Full reference (hook ordering with provider-specific hooks, MDC field
    naming rationale, sample LogQL filter) lands in a 1.0.x patch.
