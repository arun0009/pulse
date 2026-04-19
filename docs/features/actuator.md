# Live diagnostic actuator

> **Status:** Stable · **Config prefix:** `pulse.actuator` ·
> **Source:** [`io.github.arun0009.pulse.actuator`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/actuator)

## Value prop

When something looks off, the answer should never be "redeploy with debug
logging." Pulse exposes its full operational state through the actuator,
both as JSON for tools and as a single dependency-free HTML page for
humans.

## Endpoints

| Endpoint | What it shows |
|---|---|
| `/actuator/pulse` | JSON snapshot of every Pulse subsystem and its effective configuration |
| `/actuator/pulseui` | Same, rendered as a single HTML page (browser-friendly) |
| `/actuator/pulse/runtime` | Cardinality top-offenders, SLO compliance, OTel exporter freshness |
| `/actuator/pulse/effective-config` | Full resolved `PulseProperties` tree |
| `/actuator/pulse/config-hash` | Fleet-drift hash + contributing keys |
| `/actuator/pulse/slo` | Generated `PrometheusRule` YAML, ready to `kubectl apply` |
| `/actuator/health/otelExporter` | UP/DOWN based on whether the trace exporter has actually exported anything in the last `pulse.health.otel-exporter-stale-after` (default `5m`) |
| `/actuator/health/dependency` | DEGRADED when caller-side error rate for any tracked downstream crosses threshold |
| `/actuator/health/containerMemory` | DEGRADED below configured memory headroom ratio |
| `/actuator/health/jobs` | DOWN when a `@Scheduled` job hasn't succeeded inside its grace period |

## Configuration

```yaml
pulse:
  actuator:
    enabled: true
    ui-enabled: true
  health:
    otel-exporter-stale-after: 5m
```

!!! note "Expanded coverage coming"

    Full reference (custom subsystem registration in `PulseDiagnostics`,
    UI customisation, security recommendations for exposing the
    endpoints) lands in a 1.0.x patch.
