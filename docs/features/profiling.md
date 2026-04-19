# Continuous-profiling correlation

> **Status:** Stable · **Config prefix:** `pulse.profiling` ·
> **Source:** [`io.github.arun0009.pulse.profiling`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/profiling)

## Value prop

Continuous profiling (Pyroscope, Parca, Datadog Profiler) is the third leg
of observability after traces and logs. The hard part is correlating a
slow trace with the profile that captured the same time window — Pulse
makes that one click.

## What it does

Every span carries:

- `profile.id` (Grafana convention)
- `pyroscope.profile_id`
- `pulse.profile.url` (root spans only) — pre-built deep link

Pulse never bundles or starts a profiler. If you've already injected the
Pyroscope agent it is detected at startup and surfaced at
`/actuator/pulse`.

## Configuration

```yaml
pulse:
  profiling:
    enabled: true
    profile-url-template: "https://pyroscope.example.com/?query={query}"
```

!!! note "Expanded coverage coming"

    Full reference (URL template variables, Datadog Profiler integration,
    Parca compatibility notes) lands in a 1.0.x patch.
