# Changelog

All notable changes to Pulse are documented here.

This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
and follows the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

## [Unreleased]

See the repository root [`CHANGELOG.md`](https://github.com/arun0009/pulse/blob/main/CHANGELOG.md#unreleased). Core stays on by default; tenant, priority, profiling, SLO generation, OpenFeature, Caffeine bind, and profile-preset auto-apply are opt-in. Timeout-budget abort is opt-in. Logging follows Boot (Logback); Log4j2 is optional. HTTP and Kafka stamp an absolute deadline header.

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
- **SPIs** — `HostNameProvider`; public `ResourceAttributeResolver`; Apache HttpClient 5 propagation. Docs: [SPI guide](../spi.md), [API stability](../api-stability.md).
- **CI** — GraalVM native smoke build of the showcase on PRs.

### Fixed

- `@AutoConfigureAfter` targets Spring Boot 4.0.5 OTel/Micrometer auto-config classes.
- Composite classifier/fingerprint beans skipped when a same-named bean replaces them.
- IDE metadata and prose docs aligned with the split properties and behaviour.

## [1.0.0] — 2026-04-19

Initial public release — Spring Boot 4 starter for OpenTelemetry + Micrometer: cardinality and timeout-budget guardrails, structured JSON logs, caller-side dependency RED, tenant/priority propagation, jobs, DB N+1 signals, Resilience4j, Kafka lag, profiling links, OpenFeature, Caffeine, health and `/actuator/pulse`, `@PulseTest`. Third-party stacks are optional; no agent.

Details: [Documentation home](../index.md). Falsifiable hot-path numbers: `make bench` (JMH).

[2.0.0]: https://github.com/arun0009/pulse/releases/tag/v2.0.0
[1.0.0]: https://github.com/arun0009/pulse/releases/tag/v1.0.0
