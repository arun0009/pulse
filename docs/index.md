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

Spring Boot, the OpenTelemetry Java agent, Micrometer, and Log4j2 get you a
long way. But you'll still write — or wish you'd written — the boring,
unglamorous things that decide whether observability actually works at 3 AM.

Pulse is those things, in one starter, with sensible defaults.

<div class="pulse-cards" markdown>

<div class="pulse-card" markdown>
### :material-shield-lock-outline: Cardinality firewall
One mistakenly-tagged `userId` can't 100× your metrics bill. Hard cap per
metric+tag with a one-line alert. Costs ~17 ns per call.

[Read more →](features/cardinality-firewall.md)
</div>

<div class="pulse-card" markdown>
### :material-timer-sand: Timeout-budget propagation
The deadline travels with the request. Every hop sees the time remaining and
fails fast instead of holding doomed connections open.

[Read more →](features/timeout-budget.md)
</div>

<div class="pulse-card" markdown>
### :material-merge: Context that survives async
`@Async`, `@Scheduled`, custom executors, Kafka — `traceId`, `requestId`,
`userId`, tenant, budget all arrive on the worker thread automatically.

[Read more →](features/context-propagation.md)
</div>

<div class="pulse-card" markdown>
### :material-magnify-scan: Trace-context guard
A single PromQL query points at the upstream that's stripping `traceparent`.
Shipped alert tells you the route in the message.

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
The same bug groups across deploys, even when the message contains an order
ID. On the response, the active span, the metric, and the log line.

[Read more →](features/exception-fingerprints.md)
</div>

</div>

## And 19 more features

Beyond the day-one essentials, Pulse ships another nineteen features for
distributed systems and production-grade Spring shops — dependency health
map, retry-amplification detection, multi-tenant context, container-aware
memory, Kafka time-based lag, SLO-as-code, N+1 query detection,
Resilience4j observability, and more.

Each is on by default and opt-out with `pulse.<feature>.enabled=false`.
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

Pulse is **2.0** and published on Maven Central.

- [Changelog](release/changelog.md) — what's in each release
- [Source on GitHub](https://github.com/arun0009/pulse) — issues, discussions, PRs
- [Maven Central](https://central.sonatype.com/artifact/io.github.arun0009/pulse-spring-boot-starter)
