# Changelog

All notable changes to Pulse are documented here.

This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
and follows the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

## [Unreleased]

### Behavior changes

Pulse was doing too many things on by default. The day-one core is unchanged (cardinality firewall, timeout-budget *propagation*, async/Kafka context, trace-context guard, structured logs, exception fingerprints, actuator). Architecture-specific extras are now **opt-in**:

- `pulse.tenant.enabled` defaults to `false` — extracting a tenant from a client-supplied header is not safe for a generic Spring app.
- `pulse.priority.enabled` defaults to `false` — priority is a header with no load-shedder attached.
- `pulse.profiling.enabled` defaults to `false` — profiler IDs on every span are noise without a profiler.
- `pulse.slo.enabled` defaults to `false` — SLO YAML generation is a no-op until you declare objectives.
- `pulse.open-feature.enabled` defaults to `false`.
- `pulse.cache.caffeine.enabled` defaults to `false` — Micrometer already binds Caffeine when `recordStats()` is set; Pulse never forced that.
- `pulse.profile-presets.auto-apply` defaults to `false` — `SPRING_PROFILES_ACTIVE=prod` no longer silently adds `pulse-prod` (and its 10% sampling rate). Activate `pulse-prod` yourself, or set `auto-apply: true`.
- `pulse.container-memory.health-indicator-enabled` defaults to `false` — metrics stay on; readiness no longer flips `OUT_OF_SERVICE` at 10% headroom unless you ask.

Restore previous behaviour with the matching `enabled: true` (and `auto-apply: true` / `health-indicator-enabled: true`) keys.

### Added

- **`pulse.timeout-budget.abort-on-exhaustion`** (default `false`). When true, outbound HTTP calls throw `TimeoutBudgetExhaustedException` once remaining budget is at or below `minimum-budget` instead of still executing. Kafka produces are never aborted. Pulse still does **not** rewrite client read/connect timeouts; abort is skip-before-call, not mid-call cancellation.

### Fixed

- Timeout-budget docs claimed outbound calls were aborted when remaining budget was below `minimum-budget`. The interceptors stamped `0` and still made the call. The docs now match the default; abort is the new opt-in flag.

## [2.0.0] — 2026-04-20

### Breaking changes

- **`PulseProperties` split** into per-feature `@ConfigurationProperties` records — import types like `SamplingProperties` directly; YAML under `pulse.*` is unchanged.
- **`pulse.sampling.probability` removed** — use `management.tracing.sampling.probability`; keep `pulse.sampling.prefer-sampling-on-error`.
- **`DependencyClassifier` / `ErrorFingerprintStrategy`** — chain-of-responsibility: `@Nullable` continues the chain, `@Order` for precedence; first non-null wins.
- **Tracing** — Micrometer `Tracer` instead of `Span.current()`; `SpanEvents.emit` uses Observation; OpenTelemetry SDK reflection removed.
- **Auto-configuration** — one `@AutoConfiguration` per feature (lives under `.internal`); only types *outside* `.internal` are the stable public API.
- **Servlet filters** — order is RequestContext → TimeoutBudget → Tenant/Priority/Retry → TraceGuard (revalidate custom filters).

### Added

- **Enforcement mode** (DRY_RUN / ENFORCING), `POST /actuator/pulse/enforcement`.
- **Presets** — `application-pulse-{dev,prod,test,canary}.yml`; **enabled-when** request matchers; **`@PulseDryRun`**; JSR-380 on each `*Properties` record.
- **SPIs** — `HostNameProvider`; public `ResourceAttributeResolver`; Apache HttpClient 5 propagation. Docs: `docs/spi.md`, `docs/api-stability.md`.
- **CI** — GraalVM native smoke build of the showcase on PRs.

### Fixed

- `@AutoConfigureAfter` targets Spring Boot 4.0.5 OTel/Micrometer auto-config classes.
- Composite classifier/fingerprint beans skipped when a same-named bean replaces them.
- IDE metadata and prose docs aligned with the split properties and behaviour.

## [1.0.0] — 2026-04-19

Initial public release — Spring Boot 4 starter for OpenTelemetry + Micrometer: cardinality and timeout-budget guardrails, structured JSON logs, caller-side dependency RED, tenant/priority propagation, jobs, DB N+1 signals, Resilience4j, Kafka lag, profiling links, OpenFeature, Caffeine, health and `/actuator/pulse`, `@PulseTest`. Third-party stacks are optional; no agent.

Details: [README](README.md). Falsifiable hot-path numbers: `make bench` (JMH).

[2.0.0]: https://github.com/arun0009/pulse/releases/tag/v2.0.0
[1.0.0]: https://github.com/arun0009/pulse/releases/tag/v1.0.0
