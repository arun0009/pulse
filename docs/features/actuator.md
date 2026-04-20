# Live diagnostic actuator

> **TL;DR.** `/actuator/pulse` (JSON) and `/actuator/pulseui` (zero-dep
> HTML) show every Pulse setting, the last OTel exporter status, and a
> dry-run / killswitch toggle. No redeploys to inspect a running pod.

When something looks off, the answer should never be *"redeploy with debug
logging."* You should be able to ask the running app what it's doing, what
it's configured with, and what it last sent to the Collector — without
disturbing it.

**Pulse exposes its full operational state through the actuator**, both as
JSON for tooling and as a single dependency-free HTML page for humans.

## What you get

A single browser tab, no auth gymnastics, no extra dashboards to provision:

| Endpoint | What it shows |
| --- | --- |
| `/actuator/pulse` | JSON snapshot of every Pulse feature and its effective configuration |
| `/actuator/pulseui` | Same, rendered as a single HTML page (browser-friendly) |
| `/actuator/pulse/runtime` | Cardinality top-offenders, SLO compliance, OTel exporter freshness |
| `/actuator/pulse/effective-config` | Full resolved `pulse.*` configuration tree (every per-feature `*Properties` record merged) |
| `/actuator/pulse/config-hash` | [Fleet-drift](fleet-config-drift.md) hash + contributing keys |
| `/actuator/pulse/enforcement` | Current [enforcement mode](enforcement-mode.md) (`ENFORCING` or `DRY_RUN`); `POST` flips it at runtime |
| `/actuator/pulse/slo` | Generated `PrometheusRule` YAML (see [SLO-as-code](slo-as-code.md)) |

Plus the pre-built health indicators:

| Endpoint | Meaning |
| --- | --- |
| `/actuator/health/otelExporter` | UP when the trace exporter has actually exported in the last `pulse.health.otel-exporter-stale-after` (default 5m) |
| `/actuator/health/dependency` | DEGRADED when caller-side error rate for any tracked downstream crosses threshold |
| `/actuator/health/containerMemory` | `OUT_OF_SERVICE` when headroom &lt; `pulse.container-memory.headroom-critical-ratio`; `UNKNOWN` without cgroup accounting |
| `/actuator/health/jobs` | DOWN when a `@Scheduled` job hasn't succeeded inside its grace period |

## Turn it on

Nothing. The endpoints are registered automatically. Expose them through
standard Spring Boot configuration:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,pulse,pulseui,prometheus
```

## When to turn off the UI

If you don't want the HTML page exposed (e.g. on a shared management port
behind a reverse proxy you don't control), use the standard Spring Boot
endpoint controls — Pulse adds no proprietary toggle here:

```yaml
management:
  endpoint:
    pulseui:
      enabled: false                       # disable the bean entirely
  endpoints:
    web:
      exposure:
        include: health,info,pulse,prometheus   # or simply exclude pulseui
```

JSON endpoints stay available either way.

## Security note

The Pulse endpoints expose configuration and runtime state — useful for
operators, sensitive in the wrong hands. Apply the same exposure controls
you'd use for `/actuator/env`: bind management endpoints to a separate port
(`management.server.port`) and put them behind your normal admin auth.

---

**Source:** [`io.github.arun0009.pulse.actuator`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/actuator) ·
**Status:** Stable since 1.0.0
