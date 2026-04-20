# Structured logs

> **TL;DR.** OTel-aligned JSON on every line. Deploy / commit / pod / cloud
> region stamped automatically. PII masking on by default. Log → trace →
> metric pivots actually work.

JSON logs are table stakes. JSON logs that line up with what every other
modern tool expects — that's what makes log → trace → metric pivoting
actually work. Most teams either build this themselves (badly) or never get
around to it and end up grepping plain text at 3 AM.

**Pulse ships the layout, the deploy/commit/pod/region stamping, and the PII
masking.** Add the starter and every log line in your app — including the
ones that fire before Spring is fully up — comes out in the right shape.

## What you get

Every log line, including pre-Spring-Boot startup lines from background
threads, looks like this:

```json
{
  "@timestamp": "2026-04-18T15:23:11.482Z",
  "level": "INFO",
  "message": "placing order",
  "trace_id": "4c1f9b8a4e2d6f0b8c3a1e7d5b9a2c6f",
  "span_id": "9b8a4e2d6f0b8c3a",
  "service.name": "order-service",
  "service.version": "1.4.2",
  "deployment.environment": "prod",
  "vcs.ref.head.revision": "8c3a1e7d5b9a2c6f4c1f9b8a4e2d6f0b8c3a1e7d",
  "k8s.pod.name": "order-service-7d4b9c-fk2lp",
  "k8s.namespace.name": "checkout",
  "cloud.region": "us-east-1",
  "cloud.availability_zone": "us-east-1a",
  "http.request.id": "01HKGY9F7P3W2X4QV1B5KJN8MZ",
  "user.id": "u-981234"
}
```

Same `trace_id` is on the matching span and on the matching metric — pivoting
between Loki, Jaeger, and Prometheus is now `trace_id=…` instead of an
internal investigation. One LogQL query answers questions like *"slow
checkout requests on `us-east-1a` only"* without per-app glue:

```
{service_name="order-service"} | json | cloud_availability_zone="us-east-1a" | duration_ms > 1000
```

## Turn it on

Nothing. The log layout, the field set, the resource discovery, and the PII
masking are all on by default once you add the starter.

The only knob most people touch is the PII masking, and only to *disable* it
in a sealed dev environment:

```yaml
pulse:
  logging:
    pii-masking-enabled: false
```

## What it adds

The field set follows OpenTelemetry's [semantic
conventions](https://opentelemetry.io/docs/specs/semconv/) so OTel-native
sinks accept it without remapping:

| Field | Where it comes from |
| --- | --- |
| `trace_id`, `span_id` | The active OTel span (correlation across signals) |
| `service.name`, `service.version` | `spring.application.name` + `build-info.properties` |
| `deployment.environment` | `app.env` property |
| `vcs.ref.head.revision` | `git.properties` (or `OTEL_RESOURCE_ATTRIBUTES`, or `GITHUB_SHA`) |
| `host.name`, `container.id` | OS / cgroup detection at startup |
| `k8s.pod.name`, `k8s.namespace.name`, `k8s.node.name` | Kubernetes downward-API env vars |
| `cloud.provider`, `cloud.region`, `cloud.availability_zone` | AWS / GCP / Azure env vars |
| `http.request.id`, `user.id`, `pulse.tenant.id` | Pulse's request filter |
| `timeout_remaining_ms` | The active timeout budget |

All resource fields are resolved **once at startup** and cached, so there is
no per-line cost.

### PII masking

On by default. Redacts:

- Email addresses
- US Social Security numbers
- Credit-card numbers (Luhn-validated)
- `Bearer` and `Basic` Authorization tokens
- JSON properties named `password`, `secret`, `token`, `apikey` (any case)

If you have domain-specific PII shapes, add patterns via standard Log4j2
plugin parameters.

## When to skip it

You don't, really. The layout is JSON; if you don't want it, override
`logging.config` to point to your own file. Pulse only owns the field set
and the masking; everything else flows through standard Spring Boot
configuration.

The only legitimate reason to disable masking is "I'm running in a sealed
dev environment with synthetic data and the masked outputs make debugging
harder."

## Logback users

Pulse defaults to Log4j2 (Spring Boot's higher-throughput logging backend)
but supports Logback via opt-in — see the [quick start](../quickstart.md#1-add-the-dependency)
for the dependency exclusion. The Logback path produces the **exact same**
JSON shape as the Log4j2 path. Dashboards work unchanged either way.

## Where do `service.version` and the git revision come from?

Pulse reads whichever source is present at runtime, in this order:

1. JVM args (`-Dpulse.app.version=…`)
2. Classpath `META-INF/build-info.properties` + `git.properties` — the path
   most teams want, because the values travel inside the JAR through your
   registry and Docker image with no deploy-time configuration
3. `OTEL_RESOURCE_ATTRIBUTES` env var
4. Common CI env vars (`GITHUB_SHA`, `CI_COMMIT_SHA`, …)
5. Boot JAR `Implementation-Version`
6. Falls back to `"unknown"`

To wire option 2, add the
[`spring-boot-maven-plugin` `build-info` goal](https://docs.spring.io/spring-boot/maven-plugin/build-info.html)
and the
[`git-commit-id-maven-plugin`](https://github.com/git-commit-id/git-commit-id-maven-plugin)
to your `pom.xml`. No code changes.

---

**Source:** [`log4j2-spring.xml`](https://github.com/arun0009/pulse/blob/main/src/main/resources/log4j2-spring.xml) ·
[`PiiMaskingConverter.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/logging/PiiMaskingConverter.java) ·
**Runbook:** [Trace context missing](../runbooks/trace-context-missing.md) ·
**Status:** Stable since 1.0.0
