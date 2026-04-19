---
title: Pulse
hide:
  - navigation
  - toc
---

<div class="pulse-hero" markdown>

![Pulse](assets/pulse-logo.svg)

# Pulse

<p class="tagline">
  Batteries-included production observability for Spring Boot.<br/>
  One dependency. Zero agents. Everything your default stack quietly forgets.
</p>

</div>

<div class="pulse-cta" markdown>
[Get started in 3 steps :material-rocket-launch:](quickstart.md){ .md-button .md-button--primary }
[Explore the features :material-view-grid:](features/index.md){ .md-button }
</div>

## Why Pulse?

Spring Boot + the OpenTelemetry Java agent + Micrometer + Log4j2/Logback get
you a long way. Pulse fills in the **boring, unglamorous things that decide
whether observability actually works at 3 AM** — the ones you eventually build
yourself, badly, in every Spring shop.

<div class="pulse-cards" markdown>

<div class="pulse-card" markdown>
### :material-shield-lock-outline: Cardinality firewall
One bad tag can't 100× your metrics bill. Hard cap per `(meter, tag)` with
overflow bucket + alert. ~17 ns/op on the hot path.

[Read more →](features/cardinality-firewall.md)
</div>

<div class="pulse-card" markdown>
### :material-timer-sand: Timeout-budget propagation
The deadline travels with the request — across `RestTemplate`, `WebClient`,
`OkHttp`, Kafka. No service in the chain works on a stale deadline.

[Read more →](features/timeout-budget.md)
</div>

<div class="pulse-card" markdown>
### :material-merge: Context that survives async
`@Async`, `@Scheduled`, executors, Kafka — `traceId`, `requestId`, `userId`,
budget, tenant all arrive on the worker thread automatically.

[Read more →](features/context-propagation.md)
</div>

<div class="pulse-card" markdown>
### :material-magnify-scan: Trace-context guard
Find the upstream that's stripping `traceparent`. Every request is counted as
`received` or `missing`, with a shipped alert that points at the offender.

[Read more →](features/trace-context-guard.md)
</div>

<div class="pulse-card" markdown>
### :material-format-list-bulleted-square: Structured logs, OTel-aligned
Single JSON shape on every line, with deploy / commit / pod / cloud region
stamped automatically. PII masking on by default.

[Read more →](features/structured-logs.md)
</div>

<div class="pulse-card" markdown>
### :material-fingerprint: Stable exception fingerprints
The same bug groups across deploys — even when the message contains an order
id. Surfaced on the response, the active span, and as a metric.

[Read more →](features/exception-fingerprints.md)
</div>

</div>

## And 19 more subsystems

Below the fold sits another layer for teams running distributed systems at
scale — dependency health map, retry-amplification detection, multi-tenant
context, container-aware memory, Kafka time-based lag, SLO-as-code, N+1
detection, Resilience4j auto-instrumentation, and more.

Each is enabled by default and opt-out via `pulse.<subsystem>.enabled=false`.
You pay for what you turn on.

[See the full feature catalogue →](features/index.md){ .md-button }

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

## Status

Pulse is **1.0** and published on Maven Central.

- [Changelog](release/changelog.md) — what's in each release
- [Source on GitHub](https://github.com/arun0009/pulse) — issues, discussions, PRs
- [Maven Central](https://central.sonatype.com/artifact/io.github.arun0009/pulse-spring-boot-starter)
