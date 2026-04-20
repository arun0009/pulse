# Continuous-profiling correlation

> **TL;DR.** Stamps Pyroscope / Parca / Datadog Profiler IDs onto every
> span. One click from a slow trace to the matching flame-graph slice.

Continuous profiling — Pyroscope, Parca, Datadog Profiler — is the third leg
of observability after traces and logs. The hard part is correlating a slow
trace with the profile that captured the same time window. Without
correlation, the profile is just a flame graph for the whole pod.

**Pulse stamps profile IDs onto every span** so the trace UI can deep-link
straight to the matching profile slice. One click instead of a manual time
window math.

## What you get

Every span in your trace carries:

| Attribute | Convention |
| --- | --- |
| `profile.id` | Grafana convention |
| `pyroscope.profile_id` | Pyroscope convention |
| `pulse.profile.url` (root spans only) | Pre-built deep link to the matching profile slice |

In Grafana Tempo, that means the profile-correlation panel works without
extra wiring. In other UIs, the URL is one copy-paste.

## Turn it on

Set the URL of your Pyroscope-compatible UI; Pulse builds the per-trace
deep link on root spans:

```yaml
pulse:
  profiling:
    pyroscope-url: "https://pyroscope.example.com"
```

Pulse never bundles or starts a profiler. If you've already injected the
Pyroscope agent, Pulse detects it at startup and surfaces the integration
state at `/actuator/pulse`.

## What it adds

The three span attributes above. Nothing else — no metrics, no logs;
correlation alone is the value.

## When to skip it

If you don't run a continuous profiler, the attributes are just noise on
the span:

```yaml
pulse:
  profiling:
    enabled: false
```

---

**Source:** [`io.github.arun0009.pulse.profiling`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/profiling) ·
**Status:** Stable since 1.0.0
