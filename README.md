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
| **Cross-service** | Caller-side dependency RED · Request fan-out width · Retry-amplification detection · Multi-tenant context propagation |
| **Platform** | Container-aware memory (cgroup v1/v2) · Kafka time-based consumer lag · Fleet config-drift detection · Graceful-shutdown drain telemetry |
| **Integrations** | Caffeine cache stats (zero-config) · OpenFeature flag-evaluation MDC + span events |
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

## Why Pulse

The default Spring Boot + OpenTelemetry combo gives you traces, metrics, and structured logs.
What it doesn't give you is the boring, unglamorous stuff that decides whether observability
actually works at 3 AM:

| Problem in production | Default stack | Pulse |
|---|---|---|
| One typo (`tag("userId", id)`) blows up your metrics bill 100x | nothing — silently 10x cost | **Cardinality firewall** caps per-meter tag values, buckets the rest into `OVERFLOW`, alerts you to the source |
| `@Async`, `@Scheduled`, executor hops drop `traceId` / MDC | DIY decorator | **Auto-applied `TaskDecorator`** + `SchedulingConfigurer` — every hop carries context |
| Kafka producer/consumer breaks the trace chain | manual interceptor wiring | **Native producer + Spring `RecordInterceptor`** auto-registered, composes with yours |
| Slow upstream causes a retry storm; no service knows it's already late | no signal | **Timeout-budget propagation** via OTel baggage + `Pulse-Timeout-Ms` header, exhausted-call counter per transport |
| A service in the chain drops `traceparent` and you can't tell which | nothing | **TraceGuardFilter** counts `pulse.trace.received` vs `pulse.trace.missing` per route |
| Errors at low sample rate vanish from traces | crank up sampling, pay more | **Prefer-sampling-on-error** upgrades errors at span start |
| The same bug looks like 50 different errors because the message varies | manual grouping | **Stable error fingerprint** on every `ProblemDetail` and span |
| Traces vanish at shutdown / rolling deploy | last batch dropped | **Graceful OTel flush** blocks JVM exit until spans drain |
| "Did the deploy cause this?" | guess | **`pulse.deploy.info` gauge** + `app.version` / `build.commit` tags + deploy annotations on dashboard |
| Your SLOs live in a wiki | wiki | **SLO-as-code** in `application.yml` → `curl /actuator/pulse/slo \| kubectl apply -f -` |
| Logs leak emails, tokens, secrets, credit cards | DIY | **PII masking converter** built into the JSON layout |
| `/actuator/info` doesn't tell you what observability is even on | guess | **`/actuator/pulse`** + **`/actuator/pulseui`** show every subsystem's effective config and live state |
| Latency spike in a 50-service mesh — which downstream is at fault? | open every dashboard, correlate timestamps | **Dependency health map** — caller-side per-target RED + fan-out width, one panel |
| 1 user request becomes 27 calls to a struggling backend | invisible until it cascades | **Retry amplification** — `Pulse-Retry-Depth` propagates through every hop, alerts before the cascade lands |
| OOMKilled in Kubernetes — JVM had 4 GB free | JVM has no idea the cgroup limit was 2 GB | **Container memory** — cgroup v1/v2 used / limit / headroom + OOM-kill counter + health |
| Kafka consumer is "50 k messages behind" — is that bad? | depends on rate (no idea) | **Time-based lag** — `pulse.kafka.consumer.time_lag` (seconds), the actual SLO |
| Pod 17 silently runs with stale ConfigMap; p99 tail starts misbehaving | no signal until something fails | **Fleet config-drift** — `pulse.config.hash` gauge, alert on more than one hash per service |
| Rolling deploy drops in-flight requests mid-shutdown | no visibility | **Graceful-shutdown** inflight gauge + drain timer + dropped counter |
| Feature flag flipped — was *that* what broke prod? | flag and trace are in different tools | **OpenFeature** hook stamps every evaluation on MDC + span events with semconv keys |
| One tenant abuses the platform — which one, on which endpoint? | manual log spelunking | **Multi-tenant context** — tenant on MDC, baggage, outbound headers, opt-in metric tag |

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

Caller sends `Pulse-Timeout-Ms: 2000`. Pulse parses it, places the deadline on OTel baggage, and your
`RestTemplate` / `RestClient` / `WebClient` / `OkHttp` / Kafka producer all forward the
**remaining** budget on outbound calls — your downstream sees the real deadline, not the platform
default.

When the budget is exhausted *before* the outbound call fires, Pulse increments
`pulse.timeout-budget.exhausted{transport}` so you can see retry-storm precursors before they
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

**Background-job observability is built into the same wrapper.** Every `@Scheduled` method
also gets:

- `pulse.jobs.executions{job, outcome=success|failure}` — execution counter
- `pulse.jobs.duration{job, outcome}` — per-run timer (with the SLO histogram buckets)
- `pulse.jobs.in_flight{job}` — overrun detector (sustained `>1` means the job runs longer than its interval and Spring is queuing executions)
- A `jobs` health indicator that flips DOWN when any observed job hasn't succeeded within
	`pulse.jobs.failure-grace-period` (default `1h`)
- A live job table at `/actuator/pulse` showing last-success timestamp, last-failure cause,
	success/failure counts per job

No annotations, no per-job boilerplate — the same wrapping that propagates context observes
the run. ShedLock-managed jobs are observed automatically too, since they decorate the
`Runnable` *before* it reaches the scheduler.

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
`env`, `app.version`, `build.commit`, `requestId`, `errorFingerprint`, and your custom MDC keys
on **every** line — including pre-Spring-boot lines from background threads. "Which deploy
logged this?" is never a question again.

**OTel semantic-convention aliases.** Every Pulse log line also carries the OTel-canonical
field names alongside the flat ones, so OTel-native sinks (Datadog, Honeycomb, Grafana derived
fields, the Collector's `transform` processor) work without manual relabeling:

| Pulse flat name | OTel semconv alias |
|---|---|
| `traceId` / `spanId` | `trace_id` / `span_id` (OTel logs data model) |
| `service` | `service.name` |
| `env` | `deployment.environment` |
| `app.version` | `service.version` |
| `build.commit` | `vcs.ref.head.revision` |
| `requestId` | `http.request.id` |
| `userId` (from `X-User-ID`) | `user.id` |

Both names point at the same MDC key or system property — there's no double-resolution cost
and no risk of skew. The flat names will be removed in `1.0.0` after a deprecation cycle; the
OTel names are stable and will remain.

**Resource attributes — *where* the JVM is running.** On top of the per-request fields above,
every log line is stamped with the OTel resource attributes that identify the host, container,
Kubernetes pod, and cloud region. Pulse resolves them once at startup and seeds JVM system
properties so they appear on **every** thread's logs (including pre-Spring-boot startup
chatter), with no per-request cost:

| OTel attribute | Resolution sources, in priority order |
|---|---|
| `host.name` | `OTEL_RESOURCE_ATTRIBUTES`, `HOSTNAME`, `InetAddress.getLocalHost()` |
| `container.id` | `OTEL_RESOURCE_ATTRIBUTES`, 64-hex match in `/proc/self/cgroup` |
| `k8s.pod.name` | `OTEL_RESOURCE_ATTRIBUTES`, `POD_NAME` / `MY_POD_NAME`, `HOSTNAME` (only if `KUBERNETES_SERVICE_HOST` is set) |
| `k8s.namespace.name` | `OTEL_RESOURCE_ATTRIBUTES`, `POD_NAMESPACE` / `MY_POD_NAMESPACE`, `/var/run/secrets/kubernetes.io/serviceaccount/namespace` |
| `k8s.node.name` | `OTEL_RESOURCE_ATTRIBUTES`, `NODE_NAME` / `MY_NODE_NAME` |
| `cloud.provider` | inferred from env: `aws` (AWS_REGION / AWS_EXECUTION_ENV), `gcp` (GOOGLE_CLOUD_PROJECT / *_REGION), `azure` (AZURE_REGION / WEBSITE_SITE_NAME) |
| `cloud.region` | `AWS_REGION`, `GOOGLE_CLOUD_REGION`, `AZURE_REGION`, … |
| `cloud.availability_zone` | `AWS_AVAILABILITY_ZONE` |

This means dashboards like *"5xx rate per AZ"* or *"slow checkout requests on node-7 in
us-east-1a"* are one Loki/LogQL filter away — **no per-app glue, no helm-chart MDC
injection**. Undetected attributes default to `"unknown"` so the field is always present (a
clean signal you can grep for to find unconfigured deployments). Operators can always override
any value with `OTEL_RESOURCE_ATTRIBUTES=k8s.pod.name=foo,cloud.region=us-east-1`, which keeps
Pulse consistent with the rest of the OTel ecosystem.

**Logback users.** Pulse defaults to Log4j2 (transitive via `spring-boot-starter-log4j2`), but
ships an equivalent `logback-spring.xml` + `PulseLogbackEncoder` that produce the *exact same*
JSON shape — same OTel semconv aliases, same PII masking, same field set. To opt in:

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

Spring Boot's `LoggingSystem` auto-detects Logback on the classpath and loads
`logback-spring.xml` from Pulse's classpath. No further configuration required — dashboards
built on the Log4j2 path work unchanged.

#### How `app.version` and `build.commit` get resolved

Pulse cannot put values *into* your JAR or environment — Maven plugins aren't transitive, and a
Fargate task that downloads a pre-built JAR has no git context. What Pulse *does* is read
whichever source is present at runtime, in priority order, and stamps the value on every log
line and metric tag. First non-empty wins:

| # | Source | Best for |
|---|--------|----------|
| 1 | `-Dpulse.app.version=…` / `-Dpulse.build.commit=…` JVM args | Operator override |
| 2 | Classpath `META-INF/build-info.properties` + `git.properties` | **Build-once-deploy-many** (Artifactory → Docker → Fargate / EKS) — values travel inside the JAR |
| 3 | `OTEL_RESOURCE_ATTRIBUTES` env var (`service.version`, `deployment.commit`) | Kubernetes / Fargate task definitions that template these in |
| 4 | Common env vars (`GIT_COMMIT`, `GITHUB_SHA`, `CI_COMMIT_SHA`, `BUILD_VERSION`, `IMAGE_TAG`, …) | CI smoke runs and deploy pipelines that thread the SHA in |
| 5 | Boot JAR `META-INF/MANIFEST.MF` `Implementation-Version` | Free `app.version` from your pom — **no plugin needed** |
| 6 | Fallback `"unknown"` | — |

**Pick whichever fits your pipeline:**

- **Build-once-deploy-many shops** (the JAR you `mvn package` is the JAR Fargate runs, possibly weeks later) — wire the two Maven plugins in your `pom.xml`. The values are baked into the JAR at build time, survive Artifactory + Docker, and require zero deploy-time configuration:
	```xml
	<plugin>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-maven-plugin</artifactId>
			<executions>
					<execution><goals><goal>build-info</goal></goals></execution>
			</executions>
	</plugin>
	<plugin>
			<groupId>io.github.git-commit-id</groupId>
			<artifactId>git-commit-id-maven-plugin</artifactId>
			<version>9.2.0</version>
			<executions>
					<execution>
							<id>get-git-info</id>
							<goals><goal>revision</goal></goals>
							<phase>initialize</phase>
					</execution>
			</executions>
			<configuration>
					<generateGitPropertiesFile>true</generateGitPropertiesFile>
					<commitIdGenerationMode>full</commitIdGenerationMode>
					<failOnNoGitDirectory>false</failOnNoGitDirectory>
			</configuration>
	</plugin>
	```

- **Kubernetes / OTel-instrumented platforms** — set `OTEL_RESOURCE_ATTRIBUTES=service.version=1.4.2,deployment.commit=$GIT_SHA` in your deployment manifest or task definition. No XML required.

- **Do nothing** — `app.version` still resolves from your boot JAR's `Implementation-Version` (Spring Boot writes this from the pom version with zero config). Commit hash will be `unknown`.

#### PII masking

The PII masking converter redacts emails, SSNs, credit card numbers, Bearer tokens, and JSON
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

### 9. Database observability — N+1 detection, free

When Hibernate ORM is on the classpath, Pulse automatically wires a `StatementInspector` and a
servlet filter that count prepared SQL statements per request:

```
pulse.db.statements_per_request{endpoint="GET /orders/{id}"}    # distribution: p50/p95/max
pulse.db.n_plus_one.suspect{endpoint="GET /orders/{id}/items"}  # counter, fires above threshold
```

When a single request prepares more than `pulse.db.n-plus-one-threshold` statements (default
`50`), Pulse:

1. Increments the suspect counter, tagged by route template (no per-id explosion).
2. Adds a `pulse.db.n_plus_one.suspect` event to the active span with the statement count and
	endpoint as attributes — clickable from your tracing UI.
3. Logs a single structured WARN to the `pulse.db.n-plus-one` channel with the per-verb
	breakdown (e.g. `{SELECT=247, UPDATE=1}`) and the existing `traceId` / `requestId` MDC.

Slow queries are routed through Hibernate's built-in `org.hibernate.SQL_SLOW` logger; Pulse
seeds `hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS` to
`pulse.db.slow-query-threshold` (default `500ms`). Because Pulse's JSON layout already adds
`traceId` / `service` to every log line, the slow-query message is automatically correlated
with the trace that issued the query — zero extra plumbing.

The whole subsystem is gated by `@ConditionalOnClass(StatementInspector.class)` — non-JPA apps
(MongoDB, plain JDBC, REST-only) pay nothing.

### 10. Resilience4j auto-instrumentation

If your application registers a `CircuitBreakerRegistry`, `RetryRegistry`, or `BulkheadRegistry`,
Pulse attaches event consumers automatically — no annotations on your `@CircuitBreaker` /
`@Retry` methods, no manual subscribers:

```
pulse.resilience.circuit_breaker.state_transitions{name, from, to}   # CLOSED→OPEN counter
pulse.resilience.circuit_breaker.state{name}                         # live state gauge
pulse.resilience.circuit_breaker.errors{name}                        # numerator for burn-rate
pulse.resilience.retry.attempts{name}                                # silent retries surfaced
pulse.resilience.retry.exhausted{name}                               # gave up after max attempts
pulse.resilience.bulkhead.rejected{name}                             # load shed at the boundary
```

Every state transition also lands as a span event (`pulse.resilience.circuit_breaker.state_transition`)
on the active span — so a slow-trace investigation immediately tells you "this trace took 4s because
the circuit went HALF_OPEN at attempt 3 of the retry," not just "this trace was slow." Each
retry attempt likewise stamps `pulse.resilience.retry.attempt` on the parent span with the attempt
number, eliminating the silent-retry blind spot.

Cardinality is bounded by the number of breakers/retries you declare (single-digit per
service in practice).

### 11. Continuous-profiling correlation (Pyroscope-aware, vendor-neutral)

Pulse stamps every span with attributes that turn any APM into a one-click flame-graph link:

```
profile.id=<traceId>            # Grafana convention; lit up by Tempo + Grafana traces UI
pyroscope.profile_id=<traceId>  # parity with the Pyroscope agent's auto-instrumentation
pulse.profile.url=<deep link>   # root-only; populated when pulse.profiling.pyroscope-url is set
```

```yaml
pulse:
	profiling:
	pyroscope-url: https://pyroscope.example.com   # optional; enables the deep-link attribute
```

Pulse never bundles or starts a profiler. If you've already injected the Pyroscope agent
(`-javaagent:pyroscope.jar`), Pulse detects it on startup, logs a single confirmation line, and
surfaces the detection result at `/actuator/pulse` — so an operator can confirm the
profile↔trace bridge is wired without spelunking through logs. The attributes are stamped
regardless of whether the agent is actually running, since the work in trace UIs to render the
link template is the same either way.

The "click a slow span, jump to the CPU flame graph for that exact window" workflow Datadog
charges $30+/host/month for is one config line away.

### 12. Observability for observability

`/actuator/pulse` lists every subsystem and its effective config. `/actuator/pulse/runtime`
reports cardinality top-offenders, SLO compliance, and (via the bundled
`OtelExporterHealthIndicator`) whether your trace exporter has actually exported anything in the
last few minutes. `/actuator/pulseui` renders the same data as a single dependency-free HTML page.

### 13. Dependency health map (caller-side RED)

Every outbound call is recorded against a logical dependency name — derived from the target host
or set explicitly with `@PulseDependency("payment-service")`:

```
pulse.dependency.requests{dep="payment-service", method="POST", status="2xx"}
pulse.dependency.latency{dep="payment-service", method="POST"}    # timer w/ histograms
pulse.request.fan_out{endpoint="POST /checkout"}                  # distribution
pulse.request.distinct_dependencies{endpoint="POST /checkout"}    # distribution
```

Per-request fan-out width turns "this one endpoint started fanning out to 47 dependencies"
from a forensic exercise into an alert. Works across `RestTemplate`, `WebClient`, `RestClient`,
and OkHttp with zero code changes; the alert rule `PulseDependencyDegraded` ships in
`alerts/prometheus/pulse-slo-alerts.yaml`.

### 14. Retry amplification detection

Pulse propagates `Pulse-Retry-Depth` on every outbound HTTP and Kafka call and reads it on
every inbound. When the depth exceeds the threshold (default 3) the request is counted in
`pulse.retry.amplification{endpoint}`, stamped as a span event, and logged WARN with the
depth in MDC. Combined with timeout-budget propagation, you get the two leading indicators of a
cascade-in-progress before it lands as an outage.

```yaml
pulse:
	retry:
		amplification-threshold: 3
```

### 15. Multi-tenant context

A single `TenantExtractor` SPI plus three built-in extractors (header, JWT claim, subdomain)
that you turn on with `@ConditionalOnProperty`. The resolved tenant lands on MDC, OTel baggage,
every outbound HTTP / Kafka header, and (opt-in) configured meters as a tag. A separate
`pulse.tenant.max-tag-cardinality` (default 100) caps the tenant tag independently of the global
firewall so a 10k-tenant platform can't blow up its metrics bill.

```yaml
pulse:
	tenant:
		header:
			enabled: true
			name: X-Tenant-Id
		max-tag-cardinality: 100
		tagged-meters: [http.server.requests, pulse.dependency.latency]
```

Extraction priority is explicit: system property > header > JWT claim > subdomain > unknown.

### 16. Container memory observability

`CgroupMemoryReader` parses cgroup v1 and v2 inside the JVM (no JNI, no agent) so you finally
see what the kernel sees, not what the JVM thinks it has:

```
pulse.container.memory.used_bytes
pulse.container.memory.limit_bytes
pulse.container.memory.headroom_ratio
pulse.container.memory.oom_kills_total          # increments on every OOMKill
```

`ContainerMemoryHealthIndicator` flips DEGRADED below
`pulse.container.memory.warning-headroom-ratio` (default 0.15) so your readiness probe can pull
the pod out of rotation before the OOMKiller does. Off automatically on hosts where cgroups
don't exist.

### 17. Kafka time-based consumer lag

The existing `RecordInterceptor` now records `now() − record.timestamp()` per consumed record:

```
pulse.kafka.consumer.time_lag_seconds{topic, partition, group}
```

Time lag is the SLO; offset lag is the vanity metric. The shipped `PulseKafkaConsumerFallingBehind`
alert fires above 5 minutes by default. Opt-out with `pulse.kafka.consumer-time-lag-enabled=false`.

### 18. Fleet config-drift detection

`ConfigHasher` produces a deterministic hash of the resolved `pulse.*` configuration tree at
startup and exposes it as the `pulse.config.hash{hash}` gauge plus the
`/actuator/pulse/config-hash` endpoint:

```
$ curl -s /actuator/pulse/config-hash | jq '{hash, count: (.flat | length)}'
{ "hash": "a8c3…", "count": 64 }
```

The recording rule `count(distinct pulse_config_hash) by (application, env) > 1` fires
`PulseConfigDrift` — catches stale ConfigMaps, partial deploys, and one-pod env-var typos that
otherwise only surface as p99 tail latency.

### 19. Graceful-shutdown observability

A high-precedence servlet filter exposes `pulse.shutdown.inflight` so you can prove your
readiness probe is actually draining traffic before the JVM exits.
`PulseDrainObservabilityLifecycle` (a `SmartLifecycle` running just before OTel flush) measures
`pulse.shutdown.drain.duration` (timer) and `pulse.shutdown.dropped_total` (counter — requests
still in flight when the drain window expired).

```yaml
pulse:
	shutdown:
		drain:
			enabled: true
			timeout: 30s
```

### 20. OpenFeature flag-evaluation correlation

When `dev.openfeature:sdk` is on the classpath, Pulse auto-registers a hook that stamps every
flag evaluation on MDC and as an OTel-semconv span event:

```
feature_flag.key=checkout_v2  feature_flag.variant=on  feature_flag.provider_name=launchdarkly
```

If the upstream `dev.openfeature.contrib.hooks:otel` hook is also present, it is registered
automatically. Pulse never reimplements what the OpenFeature ecosystem already ships.

### 21. Caffeine cache observability (zero-config)

When Caffeine is on the classpath, every `CaffeineCacheManager` bean is post-processed to enable
`recordStats()` automatically and bound to Micrometer:

```
cache.gets{cache, manager, result=hit|miss}
cache.puts{cache, manager}
cache.evictions{cache, manager, cause}
```

Opt-out via `pulse.cache.caffeine.enabled=false`.

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
- **IDE autocomplete** — ships `META-INF/spring-configuration-metadata.json` so `pulse.*` properties autocomplete in IntelliJ / VS Code with descriptions and value hints

---

## Requirements

- Java 21+
- Spring Boot 4.0+
- Log4j2 runtime by default — Logback supported via opt-in (see [Logback users](#7-structured-json-logs-with-pii-masking) above)

## Status

Active development. See [`CHANGELOG.md`](CHANGELOG.md) for what's in each release,
[`CONTRIBUTING.md`](CONTRIBUTING.md) for the local workflow, and
[`SECURITY.md`](SECURITY.md) for vulnerability reporting.

## License

[MIT](LICENSE)
