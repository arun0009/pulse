# Web stack: Pulse is Servlet-only

> Pulse 2.x targets the **Servlet** stack only. If your application uses
> `spring-boot-starter-webflux`, most Pulse value will not activate.
> Read this page **before** picking a starter.

---

## Tl;dr

| Stack                                   | Filters | Actuator endpoint | Cardinality firewall | Async / Scheduled MDC | Kafka propagation | OTel sampler / SLO |
| --------------------------------------- | :-----: | :---------------: | :------------------: | :-------------------: | :---------------: | :----------------: |
| `spring-boot-starter-web` (Servlet)     |    Y    |         Y         |          Y           |           Y           |         Y         |         Y          |
| `spring-boot-starter-webflux` (Reactive) |    N    |       partial     |          Y           |           N (1)       |         Y         |         Y          |
| Worker / batch / CLI (`NONE`)           |   N/A   |       partial     |          Y           |           Y           |         Y         |         Y          |

(1) Reactor's context is not MDC. Pulse does not propagate MDC across reactor boundaries.

---

## What "Servlet-only" actually means

Pulse implements its inbound contract as classic Servlet filters that extend
`org.springframework.web.filter.OncePerRequestFilter` or implement `jakarta.servlet.Filter`
directly:

| Filter                       | Purpose                                                      |
| ---------------------------- | ------------------------------------------------------------ |
| `PulseRequestContextFilter`  | Hydrates MDC + `RequestContext` thread-local from headers.   |
| `TimeoutBudgetFilter`        | Opens the per-request `TimeoutBudget` scope.                 |
| `TraceGuardFilter`           | Detects requests missing `traceparent` / B3.                 |
| `TenantContextFilter`        | Resolves the current tenant and stamps it on MDC.            |
| `RequestPriorityFilter`      | Resolves request criticality and stamps it on MDC.           |
| `RetryDepthFilter`           | Seeds the retry-depth context for amplification metrics.     |
| `RequestFanoutFilter`        | Records per-request fan-out width on the active span.        |
| `InflightRequestCounter`     | Drives graceful-shutdown's drain-then-flush lifecycle.       |
| `PulseDbObservationFilter`   | Stamps DB-call counts on the request span (N+1 detection).   |

These filters do not exist on the reactive (WebFlux) pipeline. There is no equivalent
`WebFilter` shipping in the box today.

The actuator endpoint (`PulseEndpoint`, `PulseUiEndpoint`) and the `PulseExceptionHandler`
controller advice also live in `PulseWebAutoConfiguration`, which is gated on
`@ConditionalOnWebApplication(type = SERVLET)`.

## What still works on a reactive (WebFlux) app

`PulseAutoConfiguration` itself is **not** gated on the web stack, so the following beans
still wire up on a reactive app and are useful as a foundation:

- **CardinalityFirewall** — global tag-value cap on `MeterRegistry`.
- **HistogramMeterFilter** — opt-in latency histograms with sane buckets.
- **SpanEvents** — the wide-events emitter (works against `ObservationRegistry`).
- **Async / Scheduled MDC propagation** — for `@Async` methods and `@Scheduled` jobs.
  Note this is **not** the same as Reactor context propagation; reactive request flows
  are out of scope.
- **Kafka propagation** — producer + record interceptors that move MDC keys via headers.
- **OTel sampler bean** (`PreferErrorSampler`) and the **SLO rule generator**.
- **Outbound HTTP-client customizers** — `OkHttpClient`, `RestClient`, `RestTemplate`,
  `WebClient`. These propagate the request-scoped headers on outbound calls *if* something
  upstream populated MDC; on a pure reactive app, that something is your responsibility.

## What the loud startup signal does

If you start a reactive application with Pulse on the classpath, Pulse emits a single,
scannable WARN very early in the boot sequence (before any beans are wired):

```text
PULSE web-stack mismatch
────────────────────────────────────────────────────────────────────────────────
  This application starts as REACTIVE (spring-boot-starter-webflux) but Pulse
  2.x is a SERVLET-only observability starter. Every inbound filter Pulse
  ships (request-context, timeout-budget, trace-guard, tenant, priority,
  retry-depth, fan-out, inflight-counter) extends OncePerRequestFilter and
  will NOT fire on the reactive pipeline.
  ...
```

The signal is fired by `PulseWebStackEnvironmentPostProcessor`, registered in
`META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports`. It runs
before any bean creation, so there's no risk of "noise after a wall of bean errors".

The warning never fails startup. To silence it (e.g. you genuinely want the non-filter
beans on a reactive app), set:

```yaml
pulse:
  web-stack:
    suppress-reactive-warning: true
```

## Why not WebFlux today

Three honest reasons:

1. **Surface area.** Implementing the same eleven filters as reactive `WebFilter`s
   requires touching every callsite that reads MDC (because Reactor uses its own
   `Context`, not thread-locals). The work is substantial and would distract from
   stabilizing the Servlet path.
2. **MDC vs Reactor context.** Pulse leans heavily on MDC for log correlation
   (every JSON layout field is sourced from MDC). A reactive port would need
   to bridge MDC to Reactor's context at every boundary — including the user's
   own operators, which Pulse can't see.
3. **Audience.** The vast majority of Spring Boot services in production today
   are Servlet-based (Tomcat). Pulse was written to be the missing observability
   layer for that majority.

## Roadmap: WebFlux is on the table

When (and if) WebFlux support is added, it will live in a sibling artifact
(`pulse-spring-boot-starter-webflux`) so the Servlet starter stays focused. Issue
tracking the work: see the GitHub project board.

## See also

- [Concepts](concepts.md) — overall architecture.
- [Quick start](quickstart.md) — five-minute setup on a Servlet app.
- [Context propagation](features/context-propagation.md) — how MDC keys flow through
  the request.
