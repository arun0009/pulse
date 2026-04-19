# Changelog

All notable changes to Pulse are documented here.

This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
and follows the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

## [1.0.0] — 2026-04-19

First public release. Pulse 1.0 freezes the `pulse.*` configuration surface, the metric
names, the header/baggage keys, and the public Java types. Subsequent 1.x releases will
be additive only — no renames, no removals.

Pulse is a batteries-included observability starter for Spring Boot 4. It ships the
guardrails that keep observability honest in production (cardinality firewall,
timeout-budget propagation, SLO-as-code, structured JSON logs with build/commit/host/cloud
metadata) plus the per-service and between-services signals that close the most expensive
distributed-systems blind spots: caller-side dependency RED, retry amplification,
multi-tenancy, fleet config drift, request-criticality propagation, container-aware
memory, time-based Kafka lag, graceful-shutdown observability, N+1 detection, jobs,
circuit breakers, and trace-correlated CPU profiles. No agent, no bytecode tricks, no
fork — just Spring Boot auto-configuration on top of OpenTelemetry and Micrometer.

### Production guardrails
- **Cardinality firewall** — automatic per-meter cap on distinct tag values
	(`pulse.cardinality.max-tag-values-per-meter`, default 1000) with overflow bucketing
	and a `pulse.cardinality.overflow` counter so blown dimensions are visible, not silent.
- **Timeout-budget propagation** — inbound `Pulse-Timeout-Ms` header is parsed, baggage-stored,
	and deducted on every outbound hop. Exhaustion increments
	`pulse.timeout_budget.exhausted` and (for `critical` priority requests) logs at `ERROR`.
- **Trace-guard filter** — counter on inbound requests missing W3C trace context.
- **`PulseTaskDecorator`** — propagates MDC + OTel context across `@Async`,
	`CompletableFuture`, `@Scheduled`, virtual threads.
- **Common tags** — every meter is tagged with `application` and `env`.
- **Histograms + SLOs** — opinionated bucket defaults at the
	50ms / 100ms / 250ms / 500ms / 1s / 5s boundaries.
- **PII-masking log converter** for the JSON layout (emails, SSNs, credit-card numbers,
	Bearer tokens, JSON-serialized secrets).
- **Wide-event API** — `SpanEvents.emit(name, attrs)` writes a span event, increments a
	bounded counter, and stamps a structured log line in one call.
- **Sampling** — `ParentBased(TraceIdRatioBased(probability))` plus a best-effort
	error-biased sampler that lifts spans flagged `ERROR` even when the parent's flag would
	have dropped them.

### Structured logging
- **Log4j2 + Logback parity** — `PulseLogbackEncoder` produces the same JSON shape as the
	Log4j2 layout. Auto-loaded by Spring Boot's `LoggingSystem`.
- **OTel semantic-convention dual-emit** — JSON layout emits both flat (`traceId`,
	`spanId`, `service`, `env`, `app.version`, `build.commit`) and OTel-semconv (`trace_id`,
	`span_id`, `service.name`, `service.version`, `deployment.environment`,
	`vcs.ref.head.revision`) field names so logs land correlated in Grafana / Loki / Tempo
	with no manual relabel.
- **Resource-attribute log fields** — every log line is stamped with `host.name`,
	`container.id`, `k8s.pod.name`, `k8s.namespace.name`, `k8s.node.name`, `cloud.provider`,
	`cloud.region`, `cloud.availability_zone`. `ResourceAttributeResolver` reads
	`OTEL_RESOURCE_ATTRIBUTES` first (operator override), then platform env vars
	(`AWS_REGION`, `POD_NAME`, `NODE_NAME`, …), then OS-level introspection
	(`/proc/self/cgroup`, K8s service-account namespace mount). Resolved values are seeded
	as JVM system properties so every thread's logs carry them with zero per-request cost.
- **Multi-source `app.version` / `build.commit` resolution** —
	`PulseLoggingEnvironmentPostProcessor` reads JVM system properties → classpath
	`META-INF/build-info.properties` + `git.properties` → `OTEL_RESOURCE_ATTRIBUTES` →
	common CI env vars → boot JAR `Implementation-Version`. Pre-Spring-boot lines from
	background threads carry the right metadata.

### Between-services signals
- **Dependency health map (caller-side RED)** — every outbound HTTP call (RestTemplate,
	WebClient, RestClient, OkHttp) is tagged with a logical dependency name (host-derived
	or `@PulseDependency("payment-service")`) and recorded as
	`pulse.dependency.requests{dep, method, status}` +
	`pulse.dependency.latency{dep, method}`. Per-request fan-out width is captured as
	`pulse.request.fan_out{endpoint}` and `pulse.request.distinct_dependencies{endpoint}`.
	New alert rule `PulseDependencyDegraded`.
- **Topology-aware health (`DependencyHealthIndicator`)** — `/actuator/health` flips
	`DEGRADED` (or `OUT_OF_SERVICE` if configured) when a critical downstream's
	caller-side error rate exceeds `pulse.dependencies.health.error-rate-threshold`
	(default 0.05). Reads the existing `pulse.dependency.requests` meters — no extra HTTP
	calls, no circular health checks. Configure with
	`pulse.dependencies.health.critical=payment-service,inventory-service`.
- **Retry amplification detection** — Pulse propagates `Pulse-Retry-Depth` on every
	outbound hop (HTTP + Kafka) and reads it on every inbound request. When depth exceeds
	`pulse.retry.amplification-threshold` (default 3) the request is counted in
	`pulse.retry.amplification{endpoint}`, stamped as a span event, and logged `WARN`.
	New alert rule `PulseRetryAmplification`.
- **Multi-tenant context** — `TenantExtractor` SPI with three built-in extractors
	(`Pulse-Tenant-Id` header, JWT claim, subdomain) gated by `@ConditionalOnProperty`.
	Resolved tenant lands on MDC (`tenant.id`), OTel baggage, every outbound HTTP/Kafka
	header, and (opt-in) configured meters as a tag. A dedicated
	`pulse.tenant.max-tag-cardinality` (default 100) caps the tenant tag separately from
	the global firewall. Extraction priority: system property > header > JWT claim >
	subdomain > unknown.
- **Request criticality propagation** — `Pulse-Priority` inbound header (values:
	`critical|high|normal|low|background`) lands on MDC (`priority`), OTel baggage, every
	outbound HTTP/Kafka header, and (opt-in) configured meters as a tag. `critical`
	requests that exhaust their timeout budget log at `ERROR` instead of `DEBUG`.

### Per-service signals
- **Background-job observability** — every `@Scheduled` (and any `Runnable` routed
	through `TaskScheduler`) emits `pulse.jobs.executions{job, outcome}`,
	`pulse.jobs.duration{job, outcome}`, and `pulse.jobs.in_flight{job}`. A
	`JobsHealthIndicator` flips `DOWN` if any observed job hasn't succeeded within
	`pulse.jobs.failure-grace-period` (default 1h). ShedLock-managed jobs included.
- **Database observability** — when Hibernate is on the classpath, a `StatementInspector`
	counts every prepared SQL statement against the inbound request and emits
	`pulse.db.statements_per_request{endpoint}` (distribution) and
	`pulse.db.n_plus_one.suspect{endpoint}` (counter, fires above 50 by default). N+1
	events stamp a span event + a single structured `WARN` line carrying the per-verb
	breakdown. Slow queries are correlated to traces via Hibernate's
	`LOG_QUERIES_SLOWER_THAN_MS` (default 500ms).
- **Resilience4j auto-instrumentation** — when `CircuitBreakerRegistry`, `RetryRegistry`,
	or `BulkheadRegistry` are present, Pulse attaches event consumers and emits
	`pulse.resilience.circuit_breaker.state_transitions{name, from, to}`,
	`pulse.resilience.circuit_breaker.state{name}`,
	`pulse.resilience.circuit_breaker.errors{name}`,
	`pulse.resilience.retry.attempts{name}`, `pulse.resilience.retry.exhausted{name}`,
	`pulse.resilience.bulkhead.rejected{name}`. Every state transition lands as a span
	event; every retry attempt and bulkhead rejection stamps one too.
- **Continuous-profiling correlation** — every span is stamped at start with `profile.id`,
	`pyroscope.profile_id`, and (root-only, when `pulse.profiling.pyroscope-url` is set) a
	fully-qualified `pulse.profile.url` deep link to the flame graph for the trace's
	window. `PyroscopeDetector` reports agent presence at startup. Pulse never bundles a
	profiler — it observes the agent the operator already injected.
- **OpenFeature observability** — when `dev.openfeature:sdk` is on the classpath, Pulse
	auto-registers `PulseOpenFeatureMdcHook` so every flag evaluation lands on MDC
	(`feature_flag.key`, `feature_flag.variant`, `feature_flag.provider_name`) and as an
	OTel-semconv span event (`feature_flag.evaluation`). The upstream
	`dev.openfeature.contrib.hooks:otel` `OpenTelemetryHook` is registered automatically
	when present.
- **Caffeine cache observability** — when Caffeine is on the classpath, every
	`CaffeineCacheManager` bean is bound to Micrometer (`cache.gets`, `cache.puts`,
	`cache.evictions`, `cache.hit_ratio`). Pulse never mutates the operator's Caffeine
	builder; if `recordStats()` is missing it logs a one-time `WARN` per manager bean.

### Platform signals
- **Container memory observability** — `CgroupMemoryReader` parses cgroup v1 and v2 inside
	the JVM (no JNI, no agent) and exposes `pulse.container.memory.used`,
	`pulse.container.memory.limit`, `pulse.container.memory.headroom_ratio` as gauges plus
	`pulse.container.memory.oom_kills` as a counter. `ContainerMemoryHealthIndicator`
	flips `DEGRADED` below `pulse.container.memory.warning-headroom-ratio` (default 0.15).
	New alert rules `PulseContainerMemoryLowHeadroom` and `PulseContainerMemoryOomKill`.
- **Kafka time-based consumer lag** — the `RecordInterceptor` records
	`now() − record.timestamp()` per consumed record, exposed as
	`pulse.kafka.consumer.time_lag{topic, partition, group}` (gauge, base unit `seconds`).
	Time lag is the SLO; offset lag is the vanity metric. New alert rule
	`PulseKafkaConsumerFallingBehind`.
- **Fleet config-drift detection** — `ConfigHasher` produces a deterministic hash of the
	resolved `pulse.*` configuration tree at startup and exposes it as the
	`pulse.config.hash{hash}` gauge plus a `/actuator/pulse/config-hash` endpoint. New
	alert rule `PulseConfigDrift`.
- **Graceful-shutdown observability** — `InflightRequestCounter` filter exposes
	`pulse.shutdown.inflight`. `PulseDrainObservabilityLifecycle` (SmartLifecycle, runs
	just before OTel flush) measures `pulse.shutdown.drain.duration` (timer) and
	`pulse.shutdown.dropped` (counter, requests still in flight when the drain window
	expired). Configurable via `pulse.shutdown.drain.timeout` (default 30s).

### Operator surface
- **`/actuator/pulse`** — self-documenting endpoint listing every active guardrail and
	its config.
- **`/actuator/pulse/effective-config`** — resolved runtime view of the full `pulse.*`
	configuration tree.
- **`/actuator/pulse/runtime`** — live guardrail diagnostics, including cardinality
	overflow totals and top offending `(meter, tagKey)` pairs.
- **`/actuator/pulse/config-hash`** — fleet drift hash + flattened key-value view.
- **`additional-spring-configuration-metadata.json`** — IDE autocomplete with
	descriptions and value hints for every `pulse.*` property in IntelliJ and VS Code.
- **Grafana dashboard** (`dashboards/grafana/pulse-overview.json`) and **Prometheus
	burn-rate SLO alerts** (`alerts/prometheus/pulse-slo-alerts.yaml`) shipped as
	artifacts.
- **On-call runbook** for error-budget burn alerts (`docs/runbooks/error-budget-burn.md`).

### Test slice
- **`@PulseTest` Spring Boot test slice** + `PulseTestHarness` fluent assertions for
	in-memory observability testing. Auto-discovers the consumer's `@SpringBootApplication`.

### Build & release
- **Reproducible builds** — `project.build.outputTimestamp` set so the JAR is bytewise-
	identical across rebuilds.
- **CycloneDX SBOM** generated on every build.
- **JaCoCo** coverage gate (≥70% line, ≥50% branch on merged unit + integration runs).
- **Spotless + Google Java Format** enforced at `verify`.
- **JMH benchmark** profile (`mvn -Pbench package exec:java`) for the cardinality
	firewall and `SpanEvents.emit`.
- **Testcontainers + WireMock integration tests** verifying real OTLP export and
	end-to-end timeout propagation.

### Notes
- Built on Java 21, Spring Boot 4.0.5, OpenTelemetry SDK, and Micrometer.
- All third-party integrations (Hibernate, Resilience4j, OpenFeature, Caffeine, Logback,
	OkHttp, Kafka clients) are declared `<optional>true</optional>`. Consumers don't get
	them transitively; subsystems light up only when the corresponding library is on the
	classpath.
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

Reproduce with `make bench`. Numbers are not absolute (your hardware will differ); they
exist so the perf claim is falsifiable, not a vibe.

[1.0.0]: https://github.com/arun0009/pulse/releases/tag/v1.0.0
