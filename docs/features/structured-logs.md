# Structured logs (OTel-aligned)

> **Status:** Stable · **Config prefix:** `pulse.logging` ·
> **Source:** [`log4j2-spring.xml`](https://github.com/arun0009/pulse/blob/main/src/main/resources/log4j2-spring.xml),
> [`PiiMaskingConverter.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/logging/PiiMaskingConverter.java) ·
> **Runbook:** see the [trace-context-missing](../runbooks/trace-context-missing.md) for log-side debugging

## Value prop

JSON logs are table stakes. **JSON logs that line up with OpenTelemetry
semantic conventions** mean OTel-native sinks (Datadog, Honeycomb, Grafana
derived fields, the Collector's `transform` processor) work without manual
relabeling — and a single Loki/LogQL filter answers questions like *"5xx
rate per AZ"* or *"slow checkout requests on node-7 in `us-east-1a`"* with
zero per-app glue.

Pulse ships the layout, the resource resolution, and the PII masking. You
do nothing.

## What it does

The bundled `log4j2-spring.xml` (and `logback-spring.xml` if you opt into
Logback) emits a single JSON shape on every line — including the
pre-Spring-Boot lines from background threads — with **OpenTelemetry
semantic-convention names**.

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
| `pulse.tenant.id` / `pulse.priority` | Pulse multi-tenancy / criticality |
| `timeout_remaining_ms` | Time left on the propagated budget |

Pulse resolves the resource attributes **once at startup** (parsing
`OTEL_RESOURCE_ATTRIBUTES`, `/proc/self/cgroup`, K8s downward-API env vars,
cloud-provider env vars) and seeds JVM system properties so every thread's
logs carry them with no per-request cost.

## Where do `service.version` and `vcs.ref.head.revision` come from?

Pulse reads whichever source is present at runtime, in priority order:

1. JVM args (`-Dpulse.app.version=…`)
2. Classpath `META-INF/build-info.properties` + `git.properties` (the
   build-once-deploy-many path)
3. `OTEL_RESOURCE_ATTRIBUTES`
4. Common CI env vars (`GITHUB_SHA`, `CI_COMMIT_SHA`, …)
5. Boot JAR `Implementation-Version`
6. `"unknown"`

The build-once-deploy-many path wires the
[`spring-boot-maven-plugin` `build-info` goal](https://docs.spring.io/spring-boot/maven-plugin/build-info.html)
plus
[`git-commit-id-maven-plugin`](https://github.com/git-commit-id/git-commit-id-maven-plugin)
in your `pom.xml`. Values then travel inside the JAR through Artifactory
and Docker — no deploy-time configuration required.

## PII masking

The `PiiMaskingConverter` runs on every log event before it reaches the
appender, redacting:

- Email addresses
- US Social Security Numbers
- Credit-card numbers (Luhn-validated)
- `Bearer` and `Basic` Authorization tokens
- JSON properties named `password`, `secret`, `token`, `apikey` (any case)

**On by default**, because off-by-default safety is not safety. To disable
(e.g., in a development profile):

```yaml
pulse:
  logging:
    pii-masking-enabled: false
```

The full pattern set is in
[`PiiMaskingConverter.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/logging/PiiMaskingConverter.java)
and additional patterns can be configured via Log4j2 plugin parameters if
you have domain-specific PII shapes.

## What if I'm on Logback?

Pulse defaults to Log4j2 (Spring Boot's higher-throughput logging backend),
but supports Logback via opt-in. See the [Logback section in Quick start](../quickstart.md#1-add-the-dependency).

The Logback path uses `PulseLogbackEncoder` and produces the **exact same
JSON shape** as the Log4j2 path — same OTel semconv field set, same PII
masking, same resource attributes. Dashboards built on the Log4j2 path
work unchanged.

## Configuration

```yaml
pulse:
  logging:
    pii-masking-enabled: true            # default
```

That's the entire surface. Everything else lives in the bundled
`log4j2-spring.xml` (or `logback-spring.xml`); override via standard Spring
Boot mechanisms (`logging.config`) if you need to.

## Examples

### Sample log line

```json
{
  "@timestamp": "2026-04-18T15:23:11.482Z",
  "level": "INFO",
  "thread": "http-nio-8080-exec-3",
  "logger": "com.acme.orders.OrderService",
  "message": "placing order",
  "trace_id": "4c1f9b8a4e2d6f0b8c3a1e7d5b9a2c6f",
  "span_id": "9b8a4e2d6f0b8c3a",
  "service.name": "order-service",
  "service.version": "1.4.2",
  "deployment.environment": "prod",
  "vcs.ref.head.revision": "8c3a1e7d5b9a2c6f4c1f9b8a4e2d6f0b8c3a1e7d",
  "k8s.pod.name": "order-service-7d4b9c-fk2lp",
  "k8s.namespace.name": "checkout",
  "k8s.node.name": "ip-10-0-1-42",
  "cloud.region": "us-east-1",
  "cloud.availability_zone": "us-east-1a",
  "http.request.id": "01HKGY9F7P3W2X4QV1B5KJN8MZ",
  "user.id": "u-981234"
}
```

### LogQL one-liner — slow requests in one AZ

```
{service_name="order-service"} | json | cloud_availability_zone="us-east-1a" | duration_ms > 1000
```

## When to turn it off

You don't. The layout itself is not optional in any meaningful sense — if
you don't want it, override `logging.config` to point to your own file.
The only knob worth toggling is `pii-masking-enabled`, and the only valid
reason to turn it off is "I'm running in a sealed dev environment with
synthetic data and the masked outputs make debugging harder."
