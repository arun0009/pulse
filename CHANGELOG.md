# Changelog

All notable changes to Pulse are documented here.

This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
and follows the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

## [Unreleased]

### Added
- _(Nothing yet — see [`1.0.0`](#100--2026-04-19) below.)_

## [1.0.0] — 2026-04-19

First public Maven Central release. Pulse 1.0 establishes the public API surface for
production-correctness on Spring Boot 4 — cardinality firewall, timeout-budget propagation,
SLO-as-code, structured-logging-with-build-metadata, and the `@PulseTest` slice — and
commits to backwards compatibility for all `pulse.*` configuration keys, actuator
endpoints, and `io.github.arun0009.pulse.*` package classes through the 1.x line.

### Added
- **Cardinality firewall** — automatic per-meter cap on distinct tag values (default 1,000) with overflow bucketing.
- **Timeout-budget propagation** — inbound `X-Timeout-Ms` header parsed, baggage-stored, deducted on every outbound hop. Outbound interceptor for `RestTemplate` sets the residual budget.
- **Wide-event API** — `SpanEvents.emit(name, attrs)` writes a span event, increments a bounded counter, and stamps a structured log line in one call.
- **`/actuator/pulse`** — self-documenting endpoint listing every active guardrail and its config.
- **`pulse.timeout.budget.exhausted`** counter — fires when an outbound call goes out with zero budget remaining.
- **PII masking log converter** for the JSON layout (emails, SSNs, credit-card numbers, Bearer tokens, JSON-serialized secrets).
- **Trace-guard filter** — increments a counter on inbound requests missing W3C trace context.
- **`PulseTaskDecorator`** — propagates MDC + OTel context across `@Async`, `CompletableFuture`, virtual threads.
- **Common tags** — automatically tags every meter with `application` and `env`.
- **Histograms + SLOs** — opinionated bucket defaults at the 50ms / 100ms / 250ms / 500ms / 1s / 5s boundaries.
- **Audit logger** dedicated channel.
- **Grafana dashboard** (`dashboards/grafana/pulse-overview.json`) and **Prometheus burn-rate SLO alerts** (`alerts/prometheus/pulse-slo-alerts.yaml`) shipped as artifacts.
- **CycloneDX SBOM** generated on every build.
- **JaCoCo** coverage gate (≥40% line, ≥30% branch).
- **Spotless + Google Java Format** enforced at `verify`.
- **JMH benchmark** profile (`mvn -Pbench package exec:java`) for the cardinality firewall and `SpanEvents.emit`.
- **`@PulseTest` Spring Boot test slice** + `PulseTestHarness` fluent assertions for in-memory observability testing.
- **Testcontainers + WireMock integration tests** verifying real OTLP export and end-to-end timeout propagation.
- **`/actuator/pulse/effective-config`** — resolved runtime view of the full `pulse.*` configuration tree.
- **`/actuator/pulse/runtime`** — live guardrail diagnostics, including cardinality overflow totals and top offending `(meter, tagKey)` pairs.
- **`pulse.cardinality.overflow`** metric (`pulse_cardinality_overflow_total` in Prometheus) for generic overflow alerting and dashboarding.
- **On-call runbook** for error-budget burn alerts (`docs/runbooks/error-budget-burn.md`).
- **Pulse logo asset** (`assets/pulse-logo.svg`) used in project docs.
- **Multi-source `app.version` / `build.commit` resolution** — `PulseLoggingEnvironmentPostProcessor` reads JVM system properties → classpath `META-INF/build-info.properties` + `git.properties` → `OTEL_RESOURCE_ATTRIBUTES` → common CI env vars → boot JAR `Implementation-Version`, seeding both as JVM system properties so the JSON layout stamps every log line — including pre-Spring-boot lines from background threads.
- **`additional-spring-configuration-metadata.json`** — IDE autocomplete with descriptions and value hints for every `pulse.*` property in IntelliJ and VS Code.
- **Test slice now ships in the main starter** — `@PulseTest`, `PulseTestHarness`, and `PulseTestConfiguration` moved from `src/test` to `src/main/java/io/github/arun0009/pulse/test/`. JUnit, AssertJ, Spring Boot test, and OpenTelemetry SDK testing are declared `optional` so they don't propagate to production classpaths. `@PulseTest` now auto-discovers the consumer's `@SpringBootApplication` instead of forcing its own boot class.

### Build & release
- **Reproducible builds** — `project.build.outputTimestamp` set so the JAR is bytewise-identical across rebuilds.
- **POM completeness for Maven Central** — added `organization`, `issueManagement`, `ciManagement`; corrected `scm.developerConnection` URL.
- **Javadoc strict mode** — removed `failOnError=false`; broken Javadoc now fails the release build (using `doclint=all,-missing` to allow pragmatic gaps).

### Changed
- **Timeout-budget hardening** — inbound `X-Timeout-Ms` is now clamped by `pulse.timeout-budget.maximum-budget` before safety-margin/minimum logic.
- **Async propagation toggle behavior** — `pulse.async.propagation-enabled` now controls whether Pulse installs `PulseTaskDecorator` on the auto-configured executor.
- **Exception-handler coexistence** — Pulse global fallback advice is now lowest precedence so application-specific handlers can override it.
- **Alerts and dashboards** now key off `pulse_cardinality_overflow_total` instead of hardcoded overflow tag-key assumptions.
- **README** rewritten as a strict source of truth for currently implemented features/config keys.
- **Security/release metadata consistency** improved across `SECURITY.md`, release workflow comments, and docs.

### Notes
- Built on Java 21, Spring Boot 4.0.5, OpenTelemetry SDK, and Micrometer.
- No bytecode tricks. No JVM agent. No forks.

### Measured overhead (JMH, JDK 21, Apple M-series, single-shot)
| Operation | Latency |
|---|---|
| `CardinalityFirewall.map` (cached value, hot path) | ~17 ns/op |
| `CardinalityFirewall.map` (new value, under cap)   | ~80 ns/op |
| `CardinalityFirewall.map` (overflow → bucketed)    | ~90 ns/op |
| `SpanEvents.emit(name)` (counter on, log off)      | ~25 ns/op |
| `SpanEvents.emit(name, attrs)` (counter on)        | ~27 ns/op |
| `SpanEvents.emit(name, attrs)` (counter off)       |  ~4 ns/op |

Reproduce with `make bench`. Numbers are not absolute (your hardware will
differ); they exist so the perf claim is falsifiable, not a vibe.

[Unreleased]: https://github.com/arun0009/pulse/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/arun0009/pulse/releases/tag/v1.0.0
