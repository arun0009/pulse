# Features

Every feature below is **enabled by default**, and every one is opt-out via
`pulse.<subsystem>.enabled=false`. Each page follows the same template:

> **Status** · **Config prefix** · **Source** · **Runbook** · What it does ·
> Metrics · Headers / baggage / MDC · Configuration · When to turn it off

## Day-one drivers

The six things every Spring app should have on day one. None of them ship in
Spring Boot or the OTel Java agent, all of them cost less than a microsecond
on the hot path, and all of them decide whether observability actually works
at 3 AM.

<div class="grid cards" markdown>

-   :material-shield-lock-outline:{ .lg .middle } **[Cardinality firewall](cardinality-firewall.md)**

    ---

    Hard cap per `(meter, tag)` with overflow bucket and one-time WARN. Stops
    one bad tag from 100×-ing your metrics bill. ~17 ns/op cached.

-   :material-timer-sand:{ .lg .middle } **[Timeout-budget propagation](timeout-budget.md)**

    ---

    The deadline travels with the request. `RestTemplate`, `WebClient`,
    `OkHttp`, Kafka all forward the *remaining* budget. Fail-fast instead of
    holding connections.

-   :material-merge:{ .lg .middle } **[Context propagation](context-propagation.md)**

    ---

    Every `TaskExecutor` and `TaskScheduler` is wrapped automatically. Kafka
    `RecordInterceptor` chains. No `MDC.getCopyOfContextMap()` ritual.

-   :material-magnify-scan:{ .lg .middle } **[Trace-context guard](trace-context-guard.md)**

    ---

    `pulse.trace.received` vs `pulse.trace.missing` per route, with shipped
    alert. Find the upstream that's stripping `traceparent`.

-   :material-format-list-bulleted-square:{ .lg .middle } **[Structured logs](structured-logs.md)**

    ---

    OTel-semconv JSON on every line. Deploy / commit / pod / cloud region
    stamped automatically. PII masking on by default.

-   :material-fingerprint:{ .lg .middle } **[Stable exception fingerprints](exception-fingerprints.md)**

    ---

    SHA-256 over `(type + top frames)` so the same bug groups across deploys.
    On the response, the active span, and a metric.

</div>

## Distributed-systems extras

Things that matter when the system has more than two services.

- [Dependency health map](dependencies.md) — per-downstream RED metrics from
  the caller side, plus a `DependencyHealthIndicator` that reports DEGRADED
  when error rates spike.
- [Retry amplification detection](retry-amplification.md) —
  `Pulse-Retry-Depth` baggage + `pulse.retry.amplification` metric/alert when
  the chain gets too deep.
- [Multi-tenant context](multi-tenant.md) — `TenantExtractor` SPI with
  header / JWT / subdomain built-ins; tenant on MDC, baggage, outbound
  headers, and (opt-in) configured meters.
- [Request priority](priority.md) — `Pulse-Priority` header surfaced as MDC,
  baggage, and outbound headers; user-code load shedders read
  `RequestPriority.current()`.
- [Container-aware memory](container-memory.md) — cgroup v1/v2 reader (no
  agent), `pulse.container.memory.*` metrics, OOM-kill counter, readiness
  health indicator.
- [Kafka time-based lag](kafka-time-lag.md) — `now() − record.timestamp()` as
  the SLO, not offset lag.
- [Request fan-out width](fan-out.md) — `pulse.request.fan_out` per endpoint
  so you know which routes accidentally call 30 services.

## Spring extras

Things every Spring shop eventually builds, badly.

- [SLO-as-code](slo-as-code.md) — declare objectives in YAML;
  `/actuator/pulse/slo` emits multi-window, multi-burn-rate
  `PrometheusRule` YAML.
- [Resilience4j auto-instrumentation](resilience4j.md) — circuit breaker /
  retry / bulkhead events become metrics + span events automatically.
- [Background-job observability](jobs.md) — every `@Scheduled` job gets
  RED metrics, in-flight gauge, and a health indicator that flips DOWN when
  a job hasn't succeeded inside the grace period.
- [Database (N+1, slow query)](database.md) — Hibernate
  `StatementInspector` counts statements per request; alerts when a route
  crosses a threshold.
- [Cache observability (Caffeine)](cache.md) — every
  `CaffeineCacheManager` bound to Micrometer with hit/miss/eviction.
- [OpenFeature correlation](openfeature.md) — every flag evaluation lands on
  MDC and an OTel-semconv span event.
- [Continuous-profiling correlation](profiling.md) — every span carries
  `profile.id`, `pyroscope.profile_id`, and a deep link.
- [Wide-event API](wide-events.md) — `events.emit("order.placed", attrs)`
  writes a span event, increments a bounded counter, and emits a structured
  log line in one ~25 ns/op call.
- [Graceful drain + OTel flush](graceful-shutdown.md) — readiness drain
  observability + JVM exit blocks until the last span batch is exported.
- [Fleet config-drift detection](fleet-config-drift.md) — deterministic hash
  of the resolved `pulse.*` tree at startup; alert fires when a deployment
  has more than one hash.
- [Live diagnostic actuator](actuator.md) — `/actuator/pulse` (JSON) and
  `/actuator/pulseui` (HTML) for "what's running and what won."
- [Sampling](sampling.md) — `pulse.sampling.probability` plus best-effort
  `prefer-sampling-on-error` for spans the head sampler would have dropped.

## Coverage status

The six day-one drivers ship with **full feature pages** in 1.0. The
remaining 19 ship with **stubs** (status, config prefix, source, what it
does, runbook link) in 1.0; expanded "Metrics emitted / Headers / When to
turn it off" sections land in patch releases on the 1.0.x line — track
[issue #1](https://github.com/arun0009/pulse/issues) for the order.
