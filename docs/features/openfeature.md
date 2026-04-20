# OpenFeature correlation

> **TL;DR.** Stamps every OpenFeature evaluation onto MDC and the active
> span. *"Did this bug only hit users on the new flag?"* becomes a
> one-line query.

Feature flags are the most under-instrumented thing in most production
systems. *"Did the bug only affect users with the new checkout flow on?"*
should be a one-line query — but only if every flag evaluation lands on the
trace and the log line.

**Pulse stamps every OpenFeature evaluation onto MDC and the active span
automatically.** No SDK calls, no manual hook registration.

## What you get

Every log line touched by a flag evaluation carries it:

```json
{
  "message": "rendering checkout",
  "trace_id": "...",
  "feature_flag.key": "new_checkout",
  "feature_flag.variant": "treatment"
}
```

So *"slow checkout requests where `new_checkout=treatment`"* becomes a
single LogQL query, instead of grepping log files and joining by trace ID.

## Turn it on

Nothing. When `dev.openfeature:sdk` is on the classpath, Pulse registers
the hook automatically.

## What it adds

| Where | Field |
| --- | --- |
| MDC | `feature_flag.key`, `feature_flag.variant`, `feature_flag.provider_name` |
| Active span | OTel-semconv `feature_flag` event |

If the optional `dev.openfeature.contrib.hooks:otel` hook is also on the
classpath, Pulse registers it too — so consumers don't have to wire it
themselves.

## When to skip it

```yaml
pulse:
  open-feature:
    enabled: false
```

Suppresses both the MDC hook and the OTel hook registration.

---

**Source:** [`PulseOpenFeatureMdcHook.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/openfeature/PulseOpenFeatureMdcHook.java) ·
**Status:** Stable since 1.0.0
