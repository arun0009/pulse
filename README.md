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

---

## What ships in the box

Drop in Pulse and you get all of this **on by default**, zero configuration required:

| Category | What you get |
|---|---|
| **Guardrails** | Cardinality firewall · Timeout-budget propagation · Trace-context guard |
| **Context** | Auto-propagation across `@Async` / `@Scheduled` / Kafka / `CompletableFuture` |
| **Metrics** | Common tags (service, env, version, commit) · Histogram SLO buckets · Deploy-info gauge · Business metrics facade |
| **Traces** | Prefer-sampling-on-error · Stable error fingerprints · Graceful OTel flush on shutdown |
| **Logs** | Structured JSON layout · PII masking (emails, SSN, credit cards, tokens, secrets) · AUDIT channel |
| **SLO** | SLO-as-code in `application.yml` · `/actuator/pulse/slo` generates `PrometheusRule` YAML · Multi-window burn-rate alerts |
| **Diagnostics** | `/actuator/pulse` subsystem map · `/actuator/pulseui` HTML view · OTel exporter health indicator · Runtime top-offenders |
| **Ops artifacts** | Grafana dashboard · Prometheus alert rules · Incident runbooks · Production checklist |
| **Local stack** | One-command Docker Compose: OTel Collector + Prometheus + Grafana + Jaeger + Loki |
| **Testing** | `@PulseTest` Spring Boot test slice · `PulseTestHarness` fluent assertions |

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

**3. Start your app and verify**

```bash
$ curl -s localhost:8080/actuator/pulse | jq '.subsystems | keys'
[
	"audit", "cardinalityFirewall", "exceptionHandler", "histograms",
	"kafka", "logging", "requestContext", "sampling", "slo",
	"timeoutBudget", "traceGuard", "wideEvents"
]

$ curl -s localhost:8080/actuator/health | jq '.components.otelExporter'
{ "status": "UP", "details": { "lastSuccessAgeMs": 1230, "totalSuccess": 14 } }
```

That's it. No agent, no bytecode weaving, no custom runtime.
A browser-friendly view lives at **`/actuator/pulseui`**.

Don't have a Collector yet? `docker compose -f deploy/local-stack/docker-compose.yml up -d`
brings up the full pipeline in one command ([details](#run-a-local-stack)).

---

## Why Pulse

The default Spring Boot + OpenTelemetry combo gives you traces, metrics, and structured logs.
What it doesn't give you is the boring, unglamorous stuff that decides whether observability
actually works at 3 AM:

| Problem in production | Default stack | Pulse |
|---|---|---|
| One typo (`tag("userId", id)`) blows up your metrics bill 100x | nothing — silently 10x cost | **Cardinality firewall** caps per-meter tag values, buckets the rest into `OVERFLOW`, alerts you to the source |
| `@Async`, `@Scheduled`, executor hops drop `traceId` / MDC | DIY decorator | **Auto-applied `TaskDecorator`** + `SchedulingConfigurer` — every hop carries context |
| Kafka producer/consumer breaks the trace chain | manual interceptor wiring | **Native producer + Spring `RecordInterceptor`** auto-registered, composes with yours |
| Slow upstream causes a retry storm; no service knows it's already late | no signal | **Timeout-budget propagation** via OTel baggage + `X-Timeout-Ms` header, exhausted-call counter per transport |
| A service in the chain drops `traceparent` and you can't tell which | nothing | **TraceGuardFilter** counts `pulse.trace.received` vs `pulse.trace.missing` per route |
| Errors at low sample rate vanish from traces | crank up sampling, pay more | **Prefer-sampling-on-error** upgrades errors at span start |
| The same bug looks like 50 different errors because the message varies | manual grouping | **Stable error fingerprint** on every `ProblemDetail` and span |
| Traces vanish at shutdown / rolling deploy | last batch dropped | **Graceful OTel flush** blocks JVM exit until spans drain |
| "Did the deploy cause this?" | guess | **`pulse.deploy.info` gauge** + `app.version` / `build.commit` tags + deploy annotations on dashboard |
| Your SLOs live in a wiki | wiki | **SLO-as-code** in `application.yml` → `curl /actuator/pulse/slo \| kubectl apply -f -` |
| Logs leak emails, tokens, secrets, credit cards | DIY | **PII masking converter** built into the JSON layout |
| `/actuator/info` doesn't tell you what observability is even on | guess | **`/actuator/pulse`** + **`/actuator/pulseui`** show every subsystem's effective config and live state |

---

## Feature tour

### 1. Cardinality firewall

```yaml
pulse:
	cardinality:
		max-tag-values-per-meter: 1000   # default
```

Without it, this one line in your code:

```java
counter("orders.placed", "userId", id).increment();   // userId is unbounded
```

...registers one new time series per user. Pulse caps it at 1,000 distinct values per
`(meter, tag)`, buckets the rest into `OVERFLOW`, fires a one-time WARN, and increments
`pulse.cardinality.overflow{meter,tag_key}` so you can find the offender:

```
$ curl -s /actuator/pulse/runtime | jq '.cardinalityFirewall.topOffenders'
[ { "meter": "orders.placed", "tagKey": "userId", "overflowedValues": 14823 } ]
```

See [`docs/runbooks/cardinality-firewall-overflow.md`](docs/runbooks/cardinality-firewall-overflow.md).

### 2. Timeout-budget propagation

Caller sends `X-Timeout-Ms: 2000`. Pulse parses it, places the deadline on OTel baggage, and your
`RestTemplate` / `RestClient` / `WebClient` / `OkHttp` / Kafka producer all forward the
**remaining** budget on outbound calls — your downstream sees the real deadline, not the platform
default.

When the budget is exhausted *before* the outbound call fires, Pulse increments
`pulse.timeout.budget.exhausted{transport}` so you can see retry-storm precursors before they
become incidents. See
[`docs/runbooks/timeout-budget-exhausted.md`](docs/runbooks/timeout-budget-exhausted.md).

### 3. Context that survives async

Every Spring `TaskExecutor` and `TaskScheduler` is automatically wrapped with a
context-propagating decorator — no manual `MDC.getCopyOfContextMap()` / `setContextMap()` ritual:

```java
@Async
public CompletableFuture<Order> submit(Order order) {
		log.info("placing order");   // traceId, requestId, userId all present
		return CompletableFuture.completedFuture(order);
}

@Scheduled(fixedDelay = 60_000)
public void reconcile() {
		log.info("reconciling");     // same — traceId is the scheduler's, not null
}
```

Same story for Kafka — Pulse composes its `RecordInterceptor` with any of yours so MDC + the
remaining timeout budget arrive on the listener thread.

### 4. SLO-as-code

```yaml
pulse:
	slo:
		objectives:
			- name: orders-availability
				sli: availability
				target: 0.999
			- name: orders-latency
				sli: latency
				target: 0.95
				threshold: 500ms
```

```
$ curl -s http://localhost:8080/actuator/pulse/slo | kubectl apply -f -
prometheusrule.monitoring.coreos.com/pulse-slo-order-service created
```

You get multi-window, multi-burn-rate alerts (Google SRE workbook pattern) and a live in-process
projection at `/actuator/pulse/runtime` for desk-side smoke checks.

### 5. Stable error fingerprints

Every uncaught exception flowing through Pulse's `@RestControllerAdvice` is hashed into a stable,
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
`pulse.errors.unhandled{exception,fingerprint}` so dashboards cluster recurrences across deploys
and hosts.

### 6. Prefer-sampling-on-error

Standard `TraceIdRatioBased` sampling drops 95% of error spans too. Pulse composes a best-effort
"upgrade-on-error" pass: if a strong error signal is visible at span start
(`http.response.status_code >= 500`, `exception.type`, gRPC non-OK, etc.) the span is sampled
regardless of the ratio. Honest about its limit: real tail sampling needs the OTel Collector;
this is the in-process best-effort layer on top.

```yaml
pulse:
	sampling:
		probability: 0.05              # 5% baseline
		prefer-sampling-on-error: true # (default)
```

### 7. Structured JSON logs with PII masking

The bundled `log4j2-spring.xml` ships a JSON layout that emits `traceId`, `spanId`, `service`,
`env`, `requestId`, `errorFingerprint`, and your custom MDC keys on every line. The PII masking
converter redacts emails, SSNs, credit card numbers, Bearer tokens, and JSON
`password|secret|token|apikey` fields before they reach the appender — **off by default** is not
Pulse's idea of safe.

### 8. Wide-event API

One call writes a span event, increments a bounded counter, and stamps a structured log line:

```java
events.emit("order.placed", Map.of(
		"orderId", id,
		"amount",  amount,
		"tier",    customer.tier()));
```

Attributes go on the span and log (rich, high-cardinality is fine). The counter is tagged only by
event name (bounded), so you can SLO against business events without accidentally exploding
metrics cardinality.

### 9. Observability for observability

`/actuator/pulse` lists every subsystem and its effective config. `/actuator/pulse/runtime`
reports cardinality top-offenders, SLO compliance, and (via the bundled
`OtelExporterHealthIndicator`) whether your trace exporter has actually exported anything in the
last few minutes. `/actuator/pulseui` renders the same data as a single dependency-free HTML page.

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

		@Test
		void emits_business_event_with_amount(@Autowired OrderService svc) {
				svc.place(new Order(99, BigDecimal.TEN));
				pulse.assertSpanWithEvent("order.placed").hasAttribute("amount", "10");
				pulse.assertCounter("orders.placed").hasCount(1);
		}
}
```

`@PulseTest` is a Spring Boot test slice that wires an in-memory OTel SDK and a fluent
assertion harness. No external Collector, no Testcontainers, no flake.

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
- **GraalVM native hints** — `RuntimeHints` registered for all Pulse classes
- **Multi-JDK CI** — tested on Java 21 and 25

---

## Requirements

- Java 21+
- Spring Boot 4.0+
- Log4j2 runtime (the starter ships a Log4j2 layout; SLF4J/Logback users can swap in their own)

## Status

Active development. See [`CHANGELOG.md`](CHANGELOG.md) for what's in each release,
[`CONTRIBUTING.md`](CONTRIBUTING.md) for the local workflow, and
[`SECURITY.md`](SECURITY.md) for vulnerability reporting.

## License

[MIT](LICENSE)
