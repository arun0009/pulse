# Changelog

All notable changes to Pulse are documented here.

This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
and follows the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

## [0.2.0] — 2026-04-19

The "see everything" release. Pulse 0.2.0 ships six headline subsystems that close the
production-blind-spot list our 0.1.0 adopters kept hitting: their logs, their circuit
breakers, their scheduled jobs, their database queries, and their CPU profiles are all
correlated to the same trace, with zero per-app glue code.

### Added
- **OTel semantic-convention log alignment** — JSON layout now dual-emits
	`service.name`/`service`, `service.version`/`app.version`,
	`deployment.environment.name`/`env`, `vcs.ref.head.revision`/`build.commit`,
	`trace_id`/`traceId`, `span_id`/`spanId`, `http.request.id`/`requestId`, and
	`user.id`/`userId`. Logs land correlated in Grafana / Loki / Tempo with no manual relabel.
- **Logback support** — `PulseLogbackEncoder` + `logback-spring.xml` produce the same JSON
	shape as Log4j2. Auto-loaded by Spring Boot's LoggingSystem for consumers who exclude
	`spring-boot-starter-log4j2` and pull in `spring-boot-starter-logging`. Custom encoder, no
	external JSON-encoder dependency, full PII masking + OTel semconv parity.
- **Background-job observability** — every `@Scheduled` (and any Runnable routed through
	`TaskScheduler`) is wrapped to emit `pulse.jobs.executions{job, outcome}`,
	`pulse.jobs.duration{job, outcome}`, and `pulse.jobs.in_flight{job}` (overrun detector).
	A `JobsHealthIndicator` flips DOWN if any observed job hasn't succeeded within
	`pulse.jobs.failure-grace-period` (default 1h). `/actuator/pulse` exposes the live job
	table with last-success/last-failure timestamps. ShedLock-managed jobs included
	automatically.
- **Database observability** — when Hibernate is on the classpath, Pulse wires a
	`StatementInspector` that counts every prepared SQL statement against the inbound request
	and emits `pulse.db.statements_per_request{endpoint}` (distribution) and
	`pulse.db.n_plus_one.suspect{endpoint}` (counter, fires above 50 by default). N+1 events
	also stamp a span event + a single structured WARN line carrying the per-verb breakdown
	(`{SELECT=247, UPDATE=1}`) and `traceId`. Slow queries are correlated to traces by seeding
	Hibernate's `LOG_QUERIES_SLOWER_THAN_MS` to `pulse.db.slow-query-threshold` (default
	500ms) — the JSON layout adds `traceId` automatically. `@ConditionalOnClass`-gated, so
	non-JPA apps pay nothing.
- **Resilience4j auto-instrumentation** — when `CircuitBreakerRegistry`,
	`RetryRegistry`, or `BulkheadRegistry` are present, Pulse attaches event consumers and
	emits: `pulse.r4j.circuit_breaker.state_transitions{name, from, to}`,
	`pulse.r4j.circuit_breaker.state{name}`, `pulse.r4j.circuit_breaker.errors_total{name}`,
	`pulse.r4j.retry.attempts_total{name}`, `pulse.r4j.retry.exhausted_total{name}`,
	`pulse.r4j.bulkhead.rejected_total{name}`. Every state transition lands as a span event
	on the active span (`pulse.r4j.cb.state_transition`); every retry attempt stamps
	`pulse.r4j.retry.attempt` so silent retries are no longer invisible. Lazily-created
	breakers/retries are wired automatically via on-entry-added.
- **Continuous-profiling correlation** — every span is stamped at start with `profile.id`,
	`pyroscope.profile_id`, and (root-only, when `pulse.profiling.pyroscope-url` is set) a
	fully-qualified `pulse.profile.url` deep link to the flame graph for the trace's window.
	`PyroscopeDetector` reports agent presence at startup and at `/actuator/pulse`. Pulse
	never bundles a profiler — it observes the agent the operator already injected.

### Notes
- 0.2.0 is additive. No 0.1.0 configuration keys were renamed or removed; new subsystems
	are gated by their own `pulse.*` prefixes (`pulse.jobs.*`, `pulse.db.*`,
	`pulse.resilience.*`, `pulse.profiling.*`) and ship with safe `enabled=true` defaults.
- All new optional dependencies (`hibernate-core`, `spring-boot-hibernate`, the three
	`resilience4j-*` modules, `logback-classic`) are declared `<optional>true</optional>` so
	consumers don't get them transitively.

[0.2.0]: https://github.com/arun0009/pulse/releases/tag/v0.2.0

## [0.1.0] — 2026-04-19

First public release on Maven Central. Pulse is feature-complete for production-correctness
on Spring Boot 4 — cardinality firewall, timeout-budget propagation, SLO-as-code,
structured-logging-with-build-metadata, and the `@PulseTest` slice — but the API surface is
not yet frozen. Per [SemVer](https://semver.org/spec/v2.0.0.html#spec-item-4), the `0.x`
line reserves the right to evolve `pulse.*` configuration keys, actuator endpoint shapes,
and `io.github.arun0009.pulse.*` classes between minor versions based on early-adopter
feedback. A stable `1.0.0` will be cut once the surface has survived contact with real
production deployments.

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
- **JaCoCo** coverage gate (≥70% line, ≥50% branch on merged unit + integration runs).
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

[0.1.0]: https://github.com/arun0009/pulse/releases/tag/v0.1.0
