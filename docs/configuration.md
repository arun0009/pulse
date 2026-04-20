# Configuration reference

Every Pulse property lives under the `pulse.*` namespace. As of Pulse 2.0
the surface is split into one `@ConfigurationProperties` record per
feature (`CardinalityProperties`, `TimeoutBudgetProperties`,
`TenantProperties`, `SloProperties`, …) living in each feature's package
under [`io.github.arun0009.pulse`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse).
Generated metadata lives in
`META-INF/spring-configuration-metadata.json` (merged with processor output
from each `*Properties` record), so IntelliJ and VS Code autocomplete with
type hints out of the box.

## See your effective configuration at runtime

The single source of truth for "what is actually running" is the actuator:

```bash
$ curl -s localhost:8080/actuator/pulse/effective-config | jq
```

This dumps the resolved `pulse.*` configuration tree (every per-feature
`*Properties` record merged), so you can see exactly which defaults won
and which keys you overrode.

For a browser-friendly view, hit `/actuator/pulseui`.

## A production-shaped baseline

Most teams need to override only a handful of keys. Here's the
"I-want-to-tune-it-for-prod" minimum:

```yaml
spring:
  application:
    name: order-service

management:
  tracing:
    sampling:
      probability: 0.10              # 10% in prod, 1.0 in dev — Boot's standard knob
pulse:
  sampling:
    prefer-sampling-on-error: true   # rescue error spans the head sampler would drop
  timeout-budget:
    default-budget: 2s
    maximum-budget: 30s              # edge clamp
    safety-margin: 50ms
  cardinality:
    max-tag-values-per-meter: 1000
  slo:
    objectives:
      - name: orders-availability
        sli: availability
        target: 0.999
  health:
    otel-exporter-stale-after: 5m
  shutdown:
    otel-flush-timeout: 10s
```

Every subsystem also exposes a single `pulse.<subsystem>.enabled` toggle so
you can disable any feature without exclusions or conditional beans.

## Per-subsystem keys

Each [feature page](features/index.md) lists its own configuration prefix,
defaults, and gotchas. The high-traffic ones:

| Subsystem | Prefix | Key page |
|---|---|---|
| Cardinality firewall | `pulse.cardinality` | [features/cardinality-firewall.md](features/cardinality-firewall.md) |
| Timeout-budget | `pulse.timeout-budget` | [features/timeout-budget.md](features/timeout-budget.md) |
| Trace-context guard | `pulse.trace-guard` | [features/trace-context-guard.md](features/trace-context-guard.md) |
| SLO-as-code | `pulse.slo` | [features/slo-as-code.md](features/slo-as-code.md) |
| Sampling | `pulse.sampling` | [features/sampling.md](features/sampling.md) |
| Multi-tenant context | `pulse.tenant` | [features/multi-tenant.md](features/multi-tenant.md) |
| Request priority | `pulse.priority` | [features/priority.md](features/priority.md) |
| Container memory | `pulse.container-memory` | [features/container-memory.md](features/container-memory.md) |
| Kafka time-lag | `pulse.kafka` (`propagation-enabled`, `consumer-time-lag-enabled`) | [features/kafka-time-lag.md](features/kafka-time-lag.md) |
| Cache (Caffeine) | `pulse.cache.caffeine` | [features/cache.md](features/cache.md) |
| OpenFeature | `pulse.open-feature` | [features/openfeature.md](features/openfeature.md) |
| Shutdown / drain | `pulse.shutdown` | [features/graceful-shutdown.md](features/graceful-shutdown.md) |
| Health indicators | `pulse.health` | [features/actuator.md](features/actuator.md) |

## Compatibility matrix

| Component | Supported |
|---|---|
| Java | 21, 25 (CI runs both) |
| Spring Boot | 4.0+ |
| Micrometer | The version Boot 4 brings transitively (no override needed) |
| OpenTelemetry SDK | The version `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter` brings |
| Logging | Log4j2 by default; Logback supported via opt-in |
| GraalVM native | Reflection / proxy / resource hints registered via `RuntimeHints`; CI runs a native-image smoke workflow (`native-smoke.yml`) |

Pulse uses Boot 4's repackaged actuator API, the new Micrometer + OTel
starters, and Java 21 records / pattern matching. A Boot 3.x backport is
not planned for the 2.x line.

## Stability

See [API stability](api-stability.md) for the 2.x compatibility promise —
what's stable across minor versions, what's internal, and how deprecations
land.
