<p align="center">
	<img src="assets/pulse-logo.svg" alt="Pulse logo" width="128" />
</p>

<h1 align="center">Pulse</h1>

<p align="center">
	<strong>Batteries-included production observability for Spring Boot.</strong><br/>
	One dependency. Zero agents. Everything your default stack quietly forgets.
</p>

<p align="center">
	<a href="https://github.com/arun0009/pulse/actions/workflows/maven.yml"><img alt="Build" src="https://github.com/arun0009/pulse/actions/workflows/maven.yml/badge.svg?branch=main"/></a>
	<a href="https://central.sonatype.com/artifact/io.github.arun0009/pulse-spring-boot-starter"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.arun0009/pulse-spring-boot-starter?color=blue"/></a>
	<a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-yellow.svg"/></a>
	<img alt="Java 21+" src="https://img.shields.io/badge/Java-21%2B-blue?logo=openjdk&logoColor=white"/>
	<img alt="Spring Boot 4" src="https://img.shields.io/badge/Spring%20Boot-4-6DB33F?logo=springboot&logoColor=white"/>
</p>

> **Requires Spring Boot 4.x and Java 21+.** Pulse uses Boot 4's repackaged actuator
> API (`org.springframework.boot.health.contributor`), the new Micrometer + OTel
> starters, and Java 21 records / pattern matching. A Boot 3.x backport may follow in
> 1.x but is not on the 1.0 roadmap. See [Requirements](#requirements).

---

## What is Pulse?

One Spring Boot starter that adds the production observability the default
stack assumes you'll bolt on yourself: **a cardinality firewall so one bad
tag can't 100x your metrics bill, timeout-budget propagation so no service
in the chain works on a stale deadline, context that survives `@Async` /
`@Scheduled` / Kafka, structured logs with deploy / commit / Kubernetes /
cloud region stamped on every line, and stable exception fingerprints so
the same bug groups across deploys.**

That's the day-one reason. Below the fold sits another layer for teams running
distributed systems at scale — dependency health map, retry-amplification
detection, request criticality, fleet config-drift, container-aware memory,
Kafka time-based lag, multi-tenant context, SLO-as-code, OpenFeature
correlation, N+1 detection, Resilience4j auto-instrumentation, and more.
Each feature is opt-in via `pulse.x.enabled`; you pay for what you turn on.

No agent. No bytecode weaving. No custom runtime. One dependency.

---

## Quick start

**1. Add the dependency**

```xml
<dependency>
	<groupId>io.github.arun0009</groupId>
	<artifactId>pulse-spring-boot-starter</artifactId>
</dependency>
```

**2. Point at your OTel Collector**

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
```

**3. Start your app and verify your pipeline is actually exporting**

```bash
$ curl -s localhost:8080/actuator/health | jq '.components.otelExporter'
{ "status": "UP", "details": { "lastSuccessAgeMs": 1230, "totalSuccess": 14 } }
```

That's it. No agent, no bytecode weaving, no custom runtime.
For a browser-friendly view of every Pulse subsystem (what's on, what's off,
which configuration won) hit **`/actuator/pulseui`**.

Don't have a Collector yet? `docker compose -f deploy/local-stack/docker-compose.yml up -d`
brings up the full pipeline in one command ([details](#run-a-local-stack)).

---

## Six reasons to install Pulse on day one

These are the boring, unglamorous things that decide whether observability
actually works at 3 AM. Every one is on by default, none costs more than a
nanosecond on the hot path, and none of them ship in Spring Boot or the OTel
agent.

### 1. Cardinality firewall — one bad tag can't 100× your metrics bill

```java
counter("orders.placed", "userId", id).increment();   // userId is unbounded
```

Without a firewall, that one line registers a new time series per user. Pulse
caps each `(meter, tag)` at `pulse.cardinality.max-tag-values-per-meter`
(default 1000), buckets the rest into `OVERFLOW`, fires a one-time WARN with
the offending meter and tag, and increments
`pulse.cardinality.overflow{meter,tag_key}` so you can find the source:

```
$ curl -s /actuator/pulse/runtime | jq '.cardinalityFirewall.topOffenders'
[ { "meter": "orders.placed", "tagKey": "userId", "overflowedValues": 14823 } ]
```

Cached lookups cost ~17 ns/op (JMH-measured). See
[`docs/runbooks/cardinality-firewall-overflow.md`](docs/runbooks/cardinality-firewall-overflow.md).

### 2. Timeout-budget propagation — no service works on a stale deadline

The caller sets `Pulse-Timeout-Ms: 2000`. Pulse parses it, places the absolute
deadline on OTel baggage, and your `RestTemplate` / `RestClient` /
`WebClient` / `OkHttp` / Kafka producer all forward the **remaining** budget
on outbound calls. The downstream sees the real deadline, not the platform
default — which means it can fail-fast instead of holding a connection open
for the platform's 30 s default while the caller has already given up.

When the budget is exhausted *before* an outbound call fires, Pulse
increments `pulse.timeout-budget.exhausted{transport}` so retry-storm
precursors are visible *before* they become incidents. See
[`docs/runbooks/timeout-budget-exhausted.md`](docs/runbooks/timeout-budget-exhausted.md).

### 3. Context that survives `@Async`, `@Scheduled`, Kafka, executors

Every Spring `TaskExecutor` and `TaskScheduler` is automatically wrapped
with a context-propagating `TaskDecorator` — no manual
`MDC.getCopyOfContextMap()` / `setContextMap()` ritual:

```java
@Async
public CompletableFuture<Order> submit(Order order) {
		log.info("placing order");   // traceId, requestId, userId all present
		return CompletableFuture.completedFuture(order);
}

@Scheduled(fixedDelay = 60_000)
public void reconcile() {
		log.info("reconciling");     // traceId is the scheduler's, not null
}
```

Same story for Kafka — Pulse composes its `RecordInterceptor` with any of
yours so MDC + the remaining timeout budget arrive on the listener thread.

### 4. Trace-context guard — find the upstream that strips `traceparent`

Distributed traces silently lose context because some service in the chain
doesn't propagate `traceparent`. Today you only notice when half a trace is
missing in Jaeger and you have no idea which hop dropped it.

Pulse's `TraceGuardFilter` counts every inbound request as either
`pulse.trace.received{route}` or `pulse.trace.missing{route}`. The
shipped alert fires when the missing ratio exceeds a threshold per service
— pointing directly at the upstream that's stripping the header. See
[`docs/runbooks/trace-context-missing.md`](docs/runbooks/trace-context-missing.md).

### 5. Structured logs with deploy / commit / pod / region on every line

The bundled `log4j2-spring.xml` (and equivalent `logback-spring.xml` for
Logback users) emits a single JSON shape on every line — including
pre-Spring-boot lines from background threads — with **OpenTelemetry
semantic-convention names** so OTel-native sinks (Datadog, Honeycomb,
Grafana derived fields, the Collector's `transform` processor) work without
manual relabeling:

| OTel semconv field | What it tells you |
|---|---|
| `trace_id` / `span_id` | Trace correlation (OTel logs data model) |
| `service.name` / `service.version` | Which service, which build |
| `deployment.environment` | `prod` / `staging` / `dev` |
| `vcs.ref.head.revision` | The exact commit hash that produced this line |
| `host.name` / `container.id` | Where the JVM is running |
| `k8s.pod.name` / `k8s.namespace.name` / `k8s.node.name` | Kubernetes context |
| `cloud.provider` / `cloud.region` / `cloud.availability_zone` | AWS / GCP / Azure context |
| `http.request.id` / `user.id` | Per-request correlation |

Pulse resolves the resource attributes once at startup (parsing
`OTEL_RESOURCE_ATTRIBUTES`, `/proc/self/cgroup`, K8s downward-API env vars,
cloud-provider env vars) and seeds JVM system properties so every thread's
logs carry them with no per-request cost. *"5xx rate per AZ"* or *"slow
checkout requests on node-7 in us-east-1a"* is one Loki/LogQL filter away
— no per-app glue, no helm-chart MDC injection.

The PII masking converter redacts emails, SSNs, credit cards, Bearer tokens,
and JSON `password|secret|token|apikey` fields before they reach the
appender. **On by default** — off-by-default safety isn't safety.

<details>
<summary><strong>Logback users</strong> — opt out of Log4j2 (one exclusion + one dependency)</summary>

```xml
<dependency>
	<groupId>io.github.arun0009</groupId>
	<artifactId>pulse-spring-boot-starter</artifactId>
	<exclusions>
		<exclusion>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-log4j2</artifactId>
		</exclusion>
	</exclusions>
</dependency>
<dependency>
	<groupId>org.springframework.boot</groupId>
	<artifactId>spring-boot-starter-logging</artifactId>
</dependency>
```

Spring Boot's `LoggingSystem` auto-detects Logback, loads Pulse's
`logback-spring.xml`, and the `PulseLogbackEncoder` produces the *exact same*
JSON shape as Log4j2 — same OTel semconv field set, same PII masking, same
resource attributes. Dashboards built on the Log4j2 path work unchanged.
</details>

> **Where do `service.version` and `vcs.ref.head.revision` come from?** Pulse
> reads whichever source is present at runtime, in priority order: JVM args
> (`-Dpulse.app.version=…`) → classpath `META-INF/build-info.properties` +
> `git.properties` (the build-once-deploy-many path) → `OTEL_RESOURCE_ATTRIBUTES`
> → common CI env vars (`GITHUB_SHA`, `CI_COMMIT_SHA`, …) → boot JAR
> `Implementation-Version` → `"unknown"`. The build-once-deploy-many path
> wires the [`spring-boot-maven-plugin` `build-info` goal](https://docs.spring.io/spring-boot/maven-plugin/build-info.html)
> + [`git-commit-id-maven-plugin`](https://github.com/git-commit-id/git-commit-id-maven-plugin)
> in your `pom.xml`; values then travel inside the JAR through Artifactory
> and Docker without deploy-time configuration.

### 6. Stable exception fingerprints — same bug groups across deploys

Every uncaught exception flowing through Pulse's `@RestControllerAdvice` is
hashed (SHA-256 over the exception type + top frames) into a stable,
low-cardinality fingerprint and surfaced everywhere it matters:

```json
{
	"type": "urn:pulse:error:internal",
	"title": "Internal Server Error",
	"status": 500,
	"requestId": "9b8a...",
	"traceId": "4c1f...",
	"errorFingerprint": "a3f1c2d8e0"
}
```

The same fingerprint goes on the active span (`error.fingerprint`) and on
`pulse.errors.unhandled{exception,fingerprint}` so the same bug clusters
across deploys, hosts, and message variants — instead of looking like 50
different errors because the exception message contains an order id.

---

## Also included

Everything below is enabled by default unless noted, and each subsystem is
opt-out via `pulse.<subsystem>.enabled=false`. Install Pulse for the six
above and the rest works the moment you happen to need it.

### Distributed-systems essentials

- **Dependency health map (caller-side RED + fan-out width)** —
	`pulse.dependency.requests` / `pulse.dependency.latency` per logical
	downstream (host-derived or `@PulseDependency("payment-service")`),
	`pulse.request.fan_out` per endpoint. Answers "which downstream is killing
	me?" without opening 50 dashboards. Works across `RestTemplate`,
	`WebClient`, `RestClient`, OkHttp.
- **Retry amplification detection** — `Pulse-Retry-Depth` baggage propagates
	through every hop; `pulse.retry.amplification{endpoint}` + span event +
	WARN log fire when the chain depth exceeds `pulse.retry.amplification-threshold`
	(default 3). Combined with timeout-budget propagation, you have the two
	leading indicators of a cascade in flight.
- **Request criticality propagation** — `Pulse-Priority` header lands on
	baggage + MDC + outbound calls; `RequestPriority.current()` lets
	user-code load-shedders read it without touching the OTel API; critical
	requests log at WARN (not INFO) when their timeout budget is exhausted.
- **Topology-aware health** — `DependencyHealthIndicator` reads the
	dependency-map metrics (no extra HTTP calls) and reports DEGRADED when a
	downstream's caller-side error rate crosses a threshold, so
	`/actuator/health` stops lying about being green when payment-service is
	on fire.

### Platform realities

- **Container memory** — `CgroupMemoryReader` parses cgroup v1 and v2 inside
	the JVM (no JNI, no agent): `pulse.container.memory.used`,
	`pulse.container.memory.limit`, `pulse.container.memory.headroom_ratio`,
	`pulse.container.memory.oom_kills` (counter). `ContainerMemoryHealthIndicator`
	flips DEGRADED below `pulse.container.memory.warning-headroom-ratio`
	(default 0.15) so the readiness probe can pull the pod out of rotation
	before the OOMKiller does.
- **Kafka time-based consumer lag** — `pulse.kafka.consumer.time_lag` (in
	seconds, baseUnit-tagged) is `now() − record.timestamp()` on every consumed
	record. Time lag is the SLO; offset lag is the vanity metric. The shipped
	`PulseKafkaConsumerFallingBehind` alert fires above 5 minutes.
- **Fleet config-drift detection** — `ConfigHasher` deterministically hashes
	the resolved `pulse.*` configuration tree at startup; the
	`pulse.config.hash{hash}` gauge plus `/actuator/pulse/config-hash` make the
	recording rule `count(distinct pulse_config_hash) by (application, env) > 1`
	fire `PulseConfigDrift`. Catches stale ConfigMaps, partial deploys, and
	one-pod env-var typos that otherwise only surface as p99 tail latency.
- **Graceful-shutdown observability** — `pulse.shutdown.inflight` gauge
	(proves the readiness probe is draining), `pulse.shutdown.drain.duration`
	timer, and `pulse.shutdown.dropped` counter for requests still in flight
	when the drain window expires. `PulseDrainObservabilityLifecycle` runs as
	a `SmartLifecycle` just before the OTel flush.

### SLOs, alerts, and signal shape

- **SLO-as-code** — declare objectives in `application.yml`; `curl
	/actuator/pulse/slo | kubectl apply -f -` produces multi-window,
	multi-burn-rate `PrometheusRule` YAML (Google SRE workbook pattern). Live
	in-process projection at `/actuator/pulse/runtime` for desk-side checks.
- **Wide-event API** — `events.emit("order.placed", attrs)` writes a span
	event, increments a bounded counter (tagged only by event name), and
	stamps a structured log line in one call. ~25 ns/op.
- **Prefer-sampling-on-error** — best-effort upgrade pass at span start
	(`http.response.status_code >= 500`, `exception.type`, gRPC non-OK)
	rescues error spans the `TraceIdRatioBased` sampler would otherwise drop.
	Honest about its limit: real tail sampling needs the OTel Collector — this
	is the in-process layer on top.
- **Graceful OTel flush on shutdown** — JVM exit blocks until the last span
	batch drains, with a configurable timeout. Rolling deploys stop dropping
	trailing telemetry.

### Integrations

- **Background jobs / `@Scheduled`** — every observed job gets
	`pulse.jobs.executions{job, outcome}`, `pulse.jobs.duration{job, outcome}`,
	`pulse.jobs.in_flight{job}` (overrun detector), and a `jobs` health
	indicator that flips DOWN when a job hasn't succeeded inside
	`pulse.jobs.failure-grace-period` (default 1 h). ShedLock-managed jobs are
	observed automatically because the decorator wraps the `Runnable` before
	the scheduler sees it.
- **Database (N+1 detection)** — when Hibernate ORM is on the classpath, a
	`StatementInspector` + servlet filter count prepared statements per
	request. `pulse.db.statements_per_request` distribution +
	`pulse.db.n_plus_one.suspect{endpoint}` counter + span event + WARN log
	fire when a request crosses `pulse.db.n-plus-one-threshold` (default 50).
	Slow queries flow through Hibernate's built-in `org.hibernate.SQL_SLOW`
	logger, automatically correlated with the trace by Pulse's JSON layout.
- **Resilience4j** — auto-attaches event consumers to any
	`CircuitBreakerRegistry` / `RetryRegistry` / `BulkheadRegistry`:
	`pulse.resilience.circuit_breaker.{state_transitions,state,errors}`,
	`pulse.resilience.retry.{attempts,exhausted}`,
	`pulse.resilience.bulkhead.rejected`. State transitions and retry attempts
	also land as span events on the active span — eliminating the silent-retry
	blind spot in slow-trace investigations.
- **Multi-tenant context** — `TenantExtractor` SPI plus three opt-in
	built-ins (header / JWT claim / subdomain). The resolved tenant lands on
	MDC, OTel baggage, every outbound HTTP / Kafka header, and (opt-in)
	configured meters as a tag. A separate `pulse.tenant.max-tag-cardinality`
	(default 100) caps the tenant tag independently of the global firewall.
	Default header is `Pulse-Tenant-Id` (RFC 6648). Extraction priority is
	explicit: system property > header > JWT claim > subdomain > unknown.
- **OpenFeature** — when `dev.openfeature:sdk` is on the classpath, Pulse
	auto-registers a hook stamping every flag evaluation on MDC and as an OTel
	semconv span event (`feature_flag.key`, `feature_flag.variant`,
	`feature_flag.provider_name`). The upstream `contrib.hooks:otel` hook is
	registered automatically if present.
- **Continuous profiling (Pyroscope-aware, vendor-neutral)** — every span
	carries `profile.id` (Grafana convention), `pyroscope.profile_id`, and
	(root spans) `pulse.profile.url`. Pulse never bundles or starts a
	profiler; if you've already injected the Pyroscope agent it is detected
	at startup and surfaced at `/actuator/pulse`.
- **Caffeine** — when Caffeine is on the classpath, every
	`CaffeineCacheManager` bean's existing caches are bound to Micrometer
	(`cache.gets{result=hit|miss}`, `cache.puts`, `cache.evictions`). If
	`recordStats()` is missing on a manager, Pulse warns once per bean
	instead of silently overwriting your `Caffeine` builder configuration.

### Diagnostics and dev experience

- **`/actuator/pulse`** lists every subsystem and its effective config.
- **`/actuator/pulseui`** renders the same as a single dependency-free HTML page.
- **`/actuator/pulse/runtime`** reports cardinality top-offenders, SLO
	compliance, and (via `OtelExporterHealthIndicator`) whether the trace
	exporter has actually exported anything in the last few minutes.
- **`/actuator/pulse/effective-config`** dumps the entire resolved
	`PulseProperties` tree.
- **`@PulseTest`** — Spring Boot test slice + `PulseTestHarness` fluent
	assertions over both spans and Micrometer meters. Wires an in-memory
	OpenTelemetry SDK — no Collector, no Testcontainers, no flake.
- **IDE autocomplete** — `META-INF/spring-configuration-metadata.json` ships
	with every property typed, defaulted, and described, so `pulse.*` keys
	autocomplete in IntelliJ / VS Code with value hints.

---

## Run a local stack

```bash
docker compose -f deploy/local-stack/docker-compose.yml up -d
```

Brings up a complete observability pipeline with healthchecks and dependency ordering:

| Service | URL | What it does |
|---|---|---|
| **OTel Collector** | `localhost:4318` (HTTP) / `localhost:4317` (gRPC) | Receives OTLP, exports to backends. Configured with `memory_limiter`, `retry_on_failure`, `health_check`. |
| **Prometheus** | [`localhost:9090`](http://localhost:9090) | Scrapes metrics. Pre-loaded with Pulse alert rules. |
| **Grafana** | [`localhost:3000`](http://localhost:3000) (admin/admin) | Pre-provisioned with Prometheus + Jaeger + Loki datasources and the Pulse overview dashboard. |
| **Jaeger** | [`localhost:16686`](http://localhost:16686) | Trace UI. |
| **Loki** | `localhost:3100` (via Grafana) | Log aggregation. Grafana links `traceId` in logs directly to Jaeger traces. |

Point your service at `http://localhost:4318` and you're done. See
[`deploy/local-stack/README.md`](deploy/local-stack/README.md).

For a runnable end-to-end demo (with-Pulse vs without-Pulse showing the same failures), see
[`examples/showcase/`](examples/showcase/).

---

## Test slice

```java
@PulseTest
class OrderServiceTest {

	@Autowired PulseTestHarness pulse;
	@Autowired OrderService orders;

	@BeforeEach
	void resetHarness() {
		pulse.reset();
	}

	@Test
	void emitsBusinessEvent() {
		orders.place(new Order(99, BigDecimal.TEN));

		pulse.assertEvent("order.placed")
				.exists()
				.hasAttribute("amount", "10")
				.incrementedCounter("pulse.events", "event", "order.placed", 1.0);
	}
}
```

`@PulseTest` is a Spring Boot test slice that wires an in-memory OpenTelemetry SDK and a
fluent assertion harness over both spans and Micrometer meters. No external Collector,
no Testcontainers, no flake.

**Ships in the main starter** — JUnit, AssertJ, Spring Boot test, and the OTel
in-memory SDK are declared as `optional` dependencies, so they don't pollute production
classpaths but are available wherever `spring-boot-starter-test` is on the test scope
(which is everywhere a Spring Boot app is tested). No separate `-test` artifact required.

---

## Measured overhead

Every guardrail is JMH-benchmarked so the cost is provable, not a vibe:

| Operation | Latency |
|---|---|
| `CardinalityFirewall.map` — cached value, hot path | **~17 ns/op** |
| `CardinalityFirewall.map` — new value, under cap | ~80 ns/op |
| `CardinalityFirewall.map` — overflow, bucketed | ~90 ns/op |
| `SpanEvents.emit(name)` — counter on, log off | ~25 ns/op |
| `SpanEvents.emit(name, attrs)` — counter on | ~27 ns/op |

<sup>JDK 21, Apple M-series. Reproduce with `make bench`.</sup>

---

## Configuration reference

Every knob is a `pulse.*` property and every default is opinionated. Here's the
"I-want-to-tune-it-for-prod" minimum:

```yaml
spring:
	application:
		name: order-service

pulse:
	sampling:
		probability: 0.10                 # 10% in prod, 1.0 in dev
	timeout-budget:
		default-budget: 2s
		maximum-budget: 30s               # edge clamp
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

The full surface is documented in `PulseProperties` and exposed live at
`/actuator/pulse/effective-config`.

---

## Operational artifacts

| File | What it is |
|---|---|
| [`alerts/prometheus/pulse-slo-alerts.yaml`](alerts/prometheus/pulse-slo-alerts.yaml) | Multi-window burn-rate SLO alerts + cardinality, timeout, trace-context, OTel exporter alerts |
| [`dashboards/grafana/pulse-overview.json`](dashboards/grafana/pulse-overview.json) | 20-panel Grafana dashboard: golden signals, guardrails, error fingerprints, trace propagation |
| [`docs/runbooks/error-budget-burn.md`](docs/runbooks/error-budget-burn.md) | Runbook for SLO burn-rate alerts |
| [`docs/runbooks/cardinality-firewall-overflow.md`](docs/runbooks/cardinality-firewall-overflow.md) | Runbook for the cardinality alert |
| [`docs/runbooks/timeout-budget-exhausted.md`](docs/runbooks/timeout-budget-exhausted.md) | Runbook for the timeout alert |
| [`docs/runbooks/trace-context-missing.md`](docs/runbooks/trace-context-missing.md) | Runbook for the propagation alert |
| [`docs/production-checklist.md`](docs/production-checklist.md) | Pre-cutover checklist (MUST / SHOULD / NICE) |
| [`deploy/local-stack/`](deploy/local-stack/) | One-command Collector + Prometheus + Grafana + Jaeger + Loki |

---

## How Pulse compares

| Capability | Pulse | Spring Boot defaults | OTel Java agent | Vendor agents (Datadog, New Relic) |
|---|:---:|:---:|:---:|:---:|
| Drop-in starter, no agent / no weaving | **yes** | yes | no | no |
| Cardinality firewall | **yes** | no | no | no |
| Timeout-budget propagation | **yes** | no | no | no |
| Auto context across `@Async` / `@Scheduled` / Kafka | **yes** | partial | partial | partial |
| Trace propagation guard + metric | **yes** | no | no | no |
| Prefer-sampling-on-error | **yes** | no | Collector-side | partial |
| Stable error fingerprints | **yes** | no | no | yes |
| SLO-as-code with PrometheusRule generation | **yes** | no | no | no |
| Wide-event API (span + log + counter in one call) | **yes** | no | no | no |
| Live diagnostic actuator UI | **yes** | partial | no | proprietary UI |
| Structured logs + PII masking | **yes** | partial | no | partial |
| Graceful OTel flush on shutdown | **yes** | no | yes | yes |
| Pre-built Grafana dashboard + alert rules + runbooks | **yes** | no | no | proprietary |
| Spring Boot 4 / Java 21+ / GraalVM native hints | **yes** | yes | yes | varies |
| Vendor lock-in | **none** | none | none | full |

---

## Build quality

Pulse holds itself to the same bar it sets for your observability:

- **Error Prone + NullAway** — static analysis on every compile; null-safety via JSpecify `@NullMarked`
- **Spotless** — auto-formatted code on every build; no style debates in PRs
- **JaCoCo gate** — coverage floor enforced; merged unit + integration report
- **CodeQL** — GitHub security scanning on every PR and weekly schedule
- **CycloneDX SBOM** — supply-chain audit artifact generated on every build
- **Sigstore signing** — keyless provenance on every release artifact via GitHub OIDC
- **JMH benchmarks** — overhead claims are falsifiable, run on every PR
- **GraalVM native hints** — `RuntimeHints` registered for the reflection / proxy / resource edges Spring AOT cannot infer
- **Reproducible builds** — `project.build.outputTimestamp` set; bytewise-identical artifacts across rebuilds
- **Multi-JDK CI** — tested on Java 21 and 25

---

## Requirements

- Java 21+
- Spring Boot 4.0+
- Log4j2 runtime by default — Logback supported via opt-in (see [Logback users](#5-structured-logs-with-deploy--commit--pod--region-on-every-line) above)

## Status

Active development. See [`CHANGELOG.md`](CHANGELOG.md) for what's in each release,
[`CONTRIBUTING.md`](CONTRIBUTING.md) for the local workflow, and
[`SECURITY.md`](SECURITY.md) for vulnerability reporting.

## License

[MIT](LICENSE)
