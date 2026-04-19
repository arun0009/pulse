# Dependency health map

> **Status:** Stable · **Config prefix:** `pulse.dependencies` ·
> **Source:** [`io.github.arun0009.pulse.dependencies`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/dependencies)

## Value prop

Answers *"which downstream is killing me?"* without opening 50 dashboards.
Caller-side RED metrics per logical downstream — the only view that
captures retries, circuit-breaker fallbacks, and pool-saturation symptoms in
one place.

## What it does

For every outbound HTTP call (`RestTemplate`, `RestClient`, `WebClient`,
`OkHttp`), Pulse classifies the dependency by host (or by an explicit
`@PulseDependency("payment-service")` annotation if you've stamped one) and
records:

- `pulse.dependency.requests{dependency, status_class}` — RED count
- `pulse.dependency.latency{dependency, status_class}` — RED latency timer
- `pulse.request.fan_out{endpoint}` — distinct dependencies called per
  inbound request (see [fan-out](fan-out.md))

`DependencyHealthIndicator` reads these meters (no extra HTTP calls) and
reports DEGRADED when a downstream's caller-side error rate crosses
`pulse.dependencies.health.error-ratio-threshold` (default `0.10`), so
`/actuator/health` stops lying about being green when payment-service is
on fire.

## Configuration

```yaml
pulse:
  dependencies:
    enabled: true
    health:
      enabled: true
      error-ratio-threshold: 0.10
      window: 5m
```

!!! note "Expanded coverage coming"

    Full metric / tag / annotation reference and a sample dashboard panel
    land in a 1.0.x patch. Track
    [`features/dependencies` issues on GitHub](https://github.com/arun0009/pulse/issues?q=label%3Adocs+dependencies).
