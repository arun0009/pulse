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

<p align="center">
	<a href="https://arun0009.github.io/pulse/"><strong>Documentation</strong></a>
	&nbsp;·&nbsp;
	<a href="https://arun0009.github.io/pulse/quickstart/">Quick start</a>
	&nbsp;·&nbsp;
	<a href="https://arun0009.github.io/pulse/features/">Features</a>
	&nbsp;·&nbsp;
	<a href="CHANGELOG.md">Changelog</a>
</p>

---

## What is Pulse?

One Spring Boot starter that adds the production observability the default
stack assumes you'll bolt on yourself: **a cardinality firewall so one bad
tag can't 100× your metrics bill, timeout-budget propagation so no service
in the chain works on a stale deadline, context that survives `@Async` /
`@Scheduled` / Kafka, structured logs with deploy / commit / Kubernetes /
cloud region stamped on every line, and stable exception fingerprints so
the same bug groups across deploys.**

Below the fold sits another layer for teams running distributed systems at
scale — dependency health map, retry-amplification detection, request
criticality, fleet config-drift, container-aware memory, Kafka time-based
lag, multi-tenant context, SLO-as-code, OpenFeature correlation, N+1
detection, Resilience4j auto-instrumentation, and more. **Every feature is
documented in detail at <https://arun0009.github.io/pulse/>.**

No agent. No bytecode weaving. No custom runtime. One dependency.

## Quick start

```xml
<dependency>
	<groupId>io.github.arun0009</groupId>
	<artifactId>pulse-spring-boot-starter</artifactId>
	<version>1.0.0</version>
</dependency>
```

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
./mvnw spring-boot:run
curl -s localhost:8080/actuator/health/otelExporter
# {"status":"UP","details":{"lastSuccessAgeMs":1230,"totalSuccess":14}}
```

That's it. For a browser-friendly view of every Pulse subsystem (what's on,
what's off, which configuration won) hit **`/actuator/pulseui`**.

Don't have a Collector yet? `docker compose -f deploy/local-stack/docker-compose.yml up -d`
brings up the full pipeline in one command.

→ Full guide: [arun0009.github.io/pulse/quickstart/](https://arun0009.github.io/pulse/quickstart/)

## Six reasons to install Pulse on day one

The boring, unglamorous things that decide whether observability actually
works at 3 AM. Every one is on by default, none costs more than a
nanosecond on the hot path, and none ships in Spring Boot or the OTel
agent.

| Driver | What it does | Read more |
|---|---|---|
| **Cardinality firewall** | Hard cap per `(meter, tag)` with overflow bucket and one-time WARN. ~17 ns/op cached. | [features/cardinality-firewall](https://arun0009.github.io/pulse/features/cardinality-firewall/) |
| **Timeout-budget propagation** | Deadline travels with the request — across `RestTemplate`, `WebClient`, `OkHttp`, Kafka. | [features/timeout-budget](https://arun0009.github.io/pulse/features/timeout-budget/) |
| **Context across `@Async` / `@Scheduled` / Kafka** | Every `TaskExecutor` and `TaskScheduler` is wrapped automatically. No `MDC` ritual. | [features/context-propagation](https://arun0009.github.io/pulse/features/context-propagation/) |
| **Trace-context guard** | `pulse.trace.received` vs `pulse.trace.missing` per route, with shipped alert. Find the upstream stripping `traceparent`. | [features/trace-context-guard](https://arun0009.github.io/pulse/features/trace-context-guard/) |
| **Structured logs (OTel-aligned)** | OTel-semconv JSON on every line — deploy / commit / pod / cloud region stamped automatically. PII masking on by default. | [features/structured-logs](https://arun0009.github.io/pulse/features/structured-logs/) |
| **Stable exception fingerprints** | SHA-256 over `(type + top frames)` so the same bug groups across deploys. On the response, the active span, and a metric. | [features/exception-fingerprints](https://arun0009.github.io/pulse/features/exception-fingerprints/) |

## Also included (19 more subsystems)

Each is enabled by default, opt-out via `pulse.<subsystem>.enabled=false`,
and documented in detail with config keys, metrics emitted, and runbook
links at [arun0009.github.io/pulse/features/](https://arun0009.github.io/pulse/features/):

- **Distributed-systems extras** — [Dependency health map](https://arun0009.github.io/pulse/features/dependencies/),
	[Retry amplification](https://arun0009.github.io/pulse/features/retry-amplification/),
	[Multi-tenant context](https://arun0009.github.io/pulse/features/multi-tenant/),
	[Request priority](https://arun0009.github.io/pulse/features/priority/),
	[Container-aware memory](https://arun0009.github.io/pulse/features/container-memory/),
	[Kafka time-based lag](https://arun0009.github.io/pulse/features/kafka-time-lag/),
	[Request fan-out](https://arun0009.github.io/pulse/features/fan-out/).
- **Spring extras** — [SLO-as-code](https://arun0009.github.io/pulse/features/slo-as-code/),
	[Resilience4j auto-instrumentation](https://arun0009.github.io/pulse/features/resilience4j/),
	[Background-job observability](https://arun0009.github.io/pulse/features/jobs/),
	[Database (N+1, slow query)](https://arun0009.github.io/pulse/features/database/),
	[Cache (Caffeine)](https://arun0009.github.io/pulse/features/cache/),
	[OpenFeature correlation](https://arun0009.github.io/pulse/features/openfeature/),
	[Continuous-profiling correlation](https://arun0009.github.io/pulse/features/profiling/),
	[Wide-event API](https://arun0009.github.io/pulse/features/wide-events/),
	[Graceful drain + OTel flush](https://arun0009.github.io/pulse/features/graceful-shutdown/),
	[Fleet config-drift](https://arun0009.github.io/pulse/features/fleet-config-drift/),
	[Live diagnostic actuator](https://arun0009.github.io/pulse/features/actuator/),
	[Sampling](https://arun0009.github.io/pulse/features/sampling/).

## How Pulse compares

| Capability | Pulse | Spring Boot defaults | OTel Java agent | Vendor agents |
|---|:---:|:---:|:---:|:---:|
| Drop-in starter, no agent / no weaving | **yes** | yes | no | no |
| Cardinality firewall | **yes** | no | no | no |
| Timeout-budget propagation | **yes** | no | no | no |
| Auto context across `@Async` / `@Scheduled` / Kafka | **yes** | partial | partial | partial |
| Trace propagation guard + metric | **yes** | no | no | no |
| Stable error fingerprints | **yes** | no | no | yes |
| SLO-as-code with PrometheusRule generation | **yes** | no | no | no |
| Live diagnostic actuator UI | **yes** | partial | no | proprietary |
| Pre-built Grafana dashboard + alerts + runbooks | **yes** | no | no | proprietary |
| Vendor lock-in | **none** | none | none | full |

## Measured overhead

Every guardrail is JMH-benchmarked so the cost is provable:

| Operation | Latency |
|---|---|
| `CardinalityFirewall.map` — cached value, hot path | **~17 ns/op** |
| `SpanEvents.emit(name)` — counter on, log off | ~25 ns/op |

<sup>JDK 21, Apple M-series. Reproduce with `make bench`. Full table in the
docs.</sup>

## Compatibility

| Component | Supported |
|---|---|
| Java | 21, 25 (CI runs both) |
| Spring Boot | 4.0+ |
| Logging | Log4j2 by default; Logback supported via opt-in |
| GraalVM native | Reflection / proxy / resource hints registered (best-effort) |

A Boot 3.x backport may follow in the 1.x line but is not on the 1.0 roadmap.

## Build quality

- **Error Prone + NullAway** — null-safety via JSpecify `@NullMarked`
- **Spotless** — auto-formatted on every build
- **JaCoCo gate** — coverage floor enforced; merged unit + integration report
- **CodeQL** — security scanning on every PR and weekly schedule
- **CycloneDX SBOM** — supply-chain audit artifact on every build
- **Sigstore signing** — keyless provenance on every release artifact via GitHub OIDC
- **JMH benchmarks** — overhead claims are falsifiable; run in CI on every PR (non-blocking) and on demand via `make bench`
- **Multi-JDK CI** — tested on Java 21 and 25
- **Reproducible builds** — `project.build.outputTimestamp` set; bytewise-identical artifacts across rebuilds

## Status & community

**1.0** on Maven Central. See:

- [Full documentation](https://arun0009.github.io/pulse/)
- [`CHANGELOG.md`](CHANGELOG.md) — what's in each release
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — local workflow
- [`SECURITY.md`](SECURITY.md) — vulnerability reporting
- [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — community expectations

## License

[MIT](LICENSE)
