# Features

Every feature below is **on by default**, and every one is opt-out via
`pulse.<feature>.enabled=false`. Each page is short on jargon and answers
the same four questions:

- **What problem does this solve?** (Lead sentence)
- **What you get** — the metric, log line, or dashboard you actually see
- **Turn it on** — usually nothing; the right defaults are already there
- **When to skip it** — honest opt-out reasons + the exact YAML

## All 29 features at a glance

Scan the table for the feature that solves your current problem; click
through for the full page. Every feature is on by default and opt-out via
`pulse.<feature>.enabled=false` (column omitted to save space).

| Feature | What it gives you | Skip when |
| --- | --- | --- |
| [Cardinality firewall](cardinality-firewall.md) | Hard cap per `(meter, tag)`; one bad tag can't 100× the metrics bill | You already enforce this in the OTel Collector |
| [Timeout-budget propagation](timeout-budget.md) | The remaining deadline travels with the request across `RestTemplate` / `WebClient` / Kafka | You manage budgets at the gateway |
| [Context propagation](context-propagation.md) | MDC + OTel context restored on every `TaskExecutor`, `TaskScheduler`, Kafka listener | You only run synchronous request handlers |
| [Trace-context guard](trace-context-guard.md) | `pulse.trace.received` vs `pulse.trace.missing` per route + alert | Your platform already enforces ingress trace headers |
| [Structured logs](structured-logs.md) | OTel-aligned JSON; deploy/commit/pod stamped; PII masked | You already ship a custom JSON layout |
| [Stable exception fingerprints](exception-fingerprints.md) | SHA-256 over `(type + top frames)` so the same bug groups across deploys | You only run a single service and grep logs |
| [Conditional features (`enabled-when`)](conditional-features.md) | Skip any Pulse feature for synthetic monitoring / smoke tests / trusted callers | You don't need per-request gating |
| [Dependency health map](dependencies.md) | Per-downstream RED metrics from the caller; health flips DEGRADED on error spikes | You only call one downstream and own its health endpoint |
| [Retry amplification](retry-amplification.md) | `Pulse-Retry-Depth` baggage + alert *before* the retry storm becomes an incident | You don't use Resilience4j retries |
| [Multi-tenant context](multi-tenant.md) | Tenant id from header / JWT / subdomain → MDC + baggage + outbound headers + opt-in meter tag | You're not multi-tenant |
| [Request priority](priority.md) | `Pulse-Priority` on MDC + baggage + outbound; `RequestPriority.current()` for shedding | You don't load-shed by request class |
| [Container-aware memory](container-memory.md) | cgroup v1/v2 reader, OOM-kill counter, accurate memory metrics in containers | You run on bare metal |
| [Kafka time-based lag](kafka-time-lag.md) | `now() − record.timestamp()` as the SLO, not raw offset lag | You don't run Kafka consumers |
| [Request fan-out](fan-out.md) | `pulse.request.fan_out` per endpoint so you spot routes that call thirty services | You only call one downstream |
| [SLO-as-code](slo-as-code.md) | Declare SLOs in YAML; `/actuator/pulse/slo` emits a multi-burn-rate `PrometheusRule` | You don't run Prometheus |
| [Resilience4j auto-instrumentation](resilience4j.md) | Circuit breaker / retry / bulkhead events → metrics + span events with no annotations | You don't use Resilience4j |
| [Background-job observability](jobs.md) | RED metrics + in-flight gauge + health indicator per `@Scheduled` job | You don't run scheduled jobs |
| [Database (N+1, slow query)](database.md) | Hibernate hook counts statements per request; alerts on threshold cross | You don't use Hibernate / JPA |
| [Cache observability (Caffeine)](cache.md) | Every `CaffeineCacheManager` bound to Micrometer with hit/miss/eviction counts | You don't use Caffeine |
| [OpenFeature correlation](openfeature.md) | Every flag evaluation lands on MDC + span event | You don't use OpenFeature SDKs |
| [Continuous-profiling correlation](profiling.md) | Every span carries `profile.id`, `pyroscope.profile_id`, deep link | You don't run a continuous profiler |
| [Wide-event API](wide-events.md) | `events.emit("order.placed", attrs)` → span event + counter + structured log in one call | You already standardised on a custom event helper |
| [Graceful drain + OTel flush](graceful-shutdown.md) | Readiness drain visible; JVM exit blocks until the last span batch is exported | You don't care about losing the last second of traces on rolling deploys |
| [Fleet config-drift detection](fleet-config-drift.md) | Deterministic hash of resolved `pulse.*` tree; alert fires when one deployment has > 1 hash | You only run a single replica |
| [Live diagnostic actuator](actuator.md) | `/actuator/pulse` (JSON) + `/actuator/pulseui` (HTML) — what's on, off, why | You forbid actuator endpoints in production |
| [Sampling](sampling.md) | `management.tracing.sampling.probability` (Boot's standard knob) + `pulse.sampling.prefer-sampling-on-error` upgrades errors past the head sampler | You enforce sampling in the OTel Collector |
| [Dry-run / enforcement mode](enforcement-mode.md) | `POST /actuator/pulse/enforcement` flips Pulse process-wide between ENFORCING and DRY_RUN | You're confident every Pulse feature is correctly tuned |
| [Profile presets](profile-presets.md) | One-line `spring.config.import` for tuned `dev` / `prod` / `test` / `canary` configurations | You manage all configuration centrally |
| [Configuration validation](configuration-validation.md) | JSR-380 constraints on every `pulse.*` property; typos fail at startup, not 3 AM | You template all config and pre-validate it |

## Day-one drivers

Six things every Spring app should have on day one. None of them ship in
Spring Boot or the OTel Java agent, all cost less than a microsecond on the
hot path, and all of them decide whether observability actually works at
3 AM.

<div class="grid cards" markdown>

-   :material-shield-lock-outline:{ .lg .middle } **[Cardinality firewall](cardinality-firewall.md)**

    ---

    Hard cap per `(meter, tag)` with overflow bucket. Stops one bad tag from
    100×-ing your metrics bill. ~17 ns/op cached.

-   :material-timer-sand:{ .lg .middle } **[Timeout-budget propagation](timeout-budget.md)**

    ---

    The deadline travels with the request. `RestTemplate`, `WebClient`,
    `OkHttp`, Kafka all forward the *remaining* budget. Fail fast instead of
    holding doomed connections.

-   :material-merge:{ .lg .middle } **[Context propagation](context-propagation.md)**

    ---

    Every `TaskExecutor` and `TaskScheduler` is wrapped automatically. Kafka
    listeners restored from record headers. No `MDC.getCopyOfContextMap()`
    boilerplate.

-   :material-magnify-scan:{ .lg .middle } **[Trace-context guard](trace-context-guard.md)**

    ---

    `pulse.trace.received` vs `pulse.trace.missing` per route, with shipped
    alert. Find the upstream that's stripping `traceparent`.

-   :material-format-list-bulleted-square:{ .lg .middle } **[Structured logs](structured-logs.md)**

    ---

    OTel-aligned JSON on every line. Deploy / commit / pod / cloud region
    stamped automatically. PII masking on by default.

-   :material-fingerprint:{ .lg .middle } **[Stable exception fingerprints](exception-fingerprints.md)**

    ---

    SHA-256 over `(type + top frames)` so the same bug groups across
    deploys. On the response, the active span, the metric, and the log line.

</div>

## Cross-cutting

Patterns that apply across multiple features rather than a single one.

- [Conditional features (`enabled-when`)](conditional-features.md) — skip
  any Pulse feature for specific requests (synthetic monitoring, smoke
  tests, trusted internal callers) without setting `enabled: false`
  globally.

## Distributed-systems extras

What you need when the system has more than two services.

- [Dependency health map](dependencies.md) — per-downstream RED metrics from
  the caller side, plus a health indicator that flips DEGRADED when error
  rates spike.
- [Retry amplification](retry-amplification.md) — `Pulse-Retry-Depth`
  baggage + a metric that fires *before* a retry storm becomes an incident.
- [Multi-tenant context](multi-tenant.md) — extract the tenant from header,
  JWT, or subdomain; thread it through MDC, baggage, outbound headers, and
  (opt-in) configured meters.
- [Request priority](priority.md) — `Pulse-Priority` header on MDC, baggage,
  and outbound headers; user-code load shedders read
  `RequestPriority.current()`.
- [Container-aware memory](container-memory.md) — cgroup v1/v2 reader (no
  agent), accurate memory metrics in containers, OOM-kill counter,
  readiness health.
- [Kafka time-based lag](kafka-time-lag.md) — `now() − record.timestamp()`
  as the SLO, not raw offset lag.
- [Request fan-out](fan-out.md) — `pulse.request.fan_out` per endpoint so
  you know which routes accidentally call thirty services.

## Spring extras

Things every Spring shop eventually builds, badly.

- [SLO-as-code](slo-as-code.md) — declare objectives in YAML;
  `/actuator/pulse/slo` emits multi-window, multi-burn-rate
  `PrometheusRule` YAML.
- [Resilience4j auto-instrumentation](resilience4j.md) — circuit breaker,
  retry, and bulkhead events become metrics + span events automatically.
- [Background-job observability](jobs.md) — every `@Scheduled` job gets RED
  metrics, in-flight gauge, and a health indicator that flips DOWN when a
  job hasn't succeeded inside the grace period.
- [Database (N+1, slow query)](database.md) — Hibernate hook counts
  statements per request; alerts when a route crosses a threshold.
- [Cache observability (Caffeine)](cache.md) — every `CaffeineCacheManager`
  bound to Micrometer with hit/miss/eviction counts.
- [OpenFeature correlation](openfeature.md) — every flag evaluation lands
  on MDC and a span event.
- [Continuous-profiling correlation](profiling.md) — every span carries
  `profile.id`, `pyroscope.profile_id`, and a deep link.
- [Wide-event API](wide-events.md) — `events.emit("order.placed", attrs)`
  writes a span event, increments a bounded counter, and emits a structured
  log line in one ~25 ns call.
- [Graceful drain + OTel flush](graceful-shutdown.md) — readiness drain
  observability + JVM exit blocks until the last span batch is exported.
- [Fleet config-drift detection](fleet-config-drift.md) — deterministic
  hash of the resolved `pulse.*` tree at startup; alert fires when a
  deployment has more than one hash.
- [Live diagnostic actuator](actuator.md) — `/actuator/pulse` (JSON) and
  `/actuator/pulseui` (HTML) for "what's running and what won."
- [Sampling](sampling.md) — Spring Boot's standard
  `management.tracing.sampling.probability` for the head rate, plus Pulse's
  best-effort `pulse.sampling.prefer-sampling-on-error` for spans the head
  sampler would have dropped.

## Operational levers

Adoption-shortcut features that exist purely to make Pulse safer and
easier to roll out across an existing fleet.

- [Dry-run / enforcement mode](enforcement-mode.md) — process-wide
  enforce-vs-observe lever (`ENFORCING`, `DRY_RUN`); flip via
  `POST /actuator/pulse/enforcement` without a redeploy. Roll Pulse out in
  observe-only mode first, flip enforcing once dashboards confirm impact.
- [Profile presets](profile-presets.md) — one-line `spring.config.import`
  for `dev` / `prod` / `test` / `canary` profiles tuned for that
  environment.
- [Configuration validation](configuration-validation.md) — JSR-380
  constraints on every `pulse.*` property; typos fail fast at startup
  with a Pulse-friendly message.

## Coverage status

All features ship with **full feature pages**. The six day-one drivers have
the deepest coverage; the remaining subsystems document what they do, the
metrics they emit, and how to turn them off. Expanded sections land in
patch releases on the 1.0.x line — track [issue
#1](https://github.com/arun0009/pulse/issues) for the order.
