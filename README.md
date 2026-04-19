# Pulse

<p align="center">
	<img src="assets/pulse-logo.svg" alt="Pulse logo" width="120" />
</p>

Production correctness for Spring Boot: context propagation, observability guardrails, and sane defaults that prevent common distributed-systems failures.

Pulse is an opinionated Spring Boot starter built on OpenTelemetry, Micrometer, and Boot auto-configuration. No bytecode weaving, no Java agent requirement, no custom runtime.

## Why Pulse

Pulse focuses on failures that repeatedly hurt Spring services in production:

- **Trace/context loss on thread hops**: `@Async` and executor hops keep MDC + OTel context.
- **Runaway metric cardinality**: caps distinct tag values per meter and rewrites overflow values.
- **Cascading latency**: propagates remaining timeout budget across hops via baggage and headers.
- **Disconnected incident signals**: one call emits a span event, structured log, and bounded metric.
- **Unknown runtime state**: `/actuator/pulse` shows enabled subsystems, effective config, and live runtime diagnostics.

## Quick start

1. Add the dependency:

```xml
<dependency>
		<groupId>io.github.arun0009</groupId>
		<artifactId>pulse-spring-boot-starter</artifactId>
		<version>2.0.0-SNAPSHOT</version>
</dependency>
```

2. Set your OTLP endpoint (for example via env):

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
```

3. Start your app and verify:
- startup banner includes `PULSE`
- `GET /actuator/pulse` returns subsystem status
- traces/logs/metrics arrive in your backend

For a runnable demo, use `examples/failure-demo/`.

## What ships today

The following are implemented in this repository:

- `pulse.context.*`: request/correlation/user/tenant/idempotency context extraction into MDC.
- `pulse.trace-guard.*`: detects inbound requests missing trace context.
- `pulse.sampling.probability`: parent-based trace sampling ratio.
- `pulse.async.*`: auto-configurable `taskExecutor` and optional context-propagating task decorator.
- `pulse.kafka.propagation-enabled`: propagation interceptors when Spring Kafka is present.
- `pulse.exception-handler.enabled`: RFC 7807 response with `requestId` and `traceId`.
- `pulse.audit.enabled`: dedicated audit logger channel.
- `pulse.cardinality.*`: cardinality firewall with overflow bucketing.
- `pulse.timeout-budget.*`: inbound timeout parsing, max clamp, safety margin, and outbound propagation header.
- `pulse.wide-events.*`: `SpanEvents.emit(...)` for span + log + bounded counter emission.
- `pulse.logging.pii-masking-enabled`: log masking converter support for JSON layout.
- `pulse.histograms.*`: latency histogram defaults for common meter families.
- `pulse.slo.*`: SLO objectives rendered as PrometheusRule YAML at `/actuator/pulse/slo`.
- `pulse.banner.enabled`: startup summary banner.
- Actuator deep views:
	- `/actuator/pulse/effective-config` for resolved `pulse.*`
	- `/actuator/pulse/runtime` for live guardrail state (including top cardinality offenders)

## Minimal configuration

```yaml
spring:
	application:
		name: order-service

pulse:
	sampling:
		probability: 0.10
	timeout-budget:
		inbound-header: X-Timeout-Ms
		outbound-header: X-Timeout-Ms
		default-budget: 2s
		maximum-budget: 30s
		safety-margin: 50ms
		minimum-budget: 100ms
	cardinality:
		max-tag-values-per-meter: 1000
		overflow-value: OVERFLOW
	wide-events:
		counter-enabled: true
		log-enabled: true
```

## Operational artifacts

- Alerts: `alerts/prometheus/pulse-slo-alerts.yaml`
- Dashboard: `dashboards/grafana/pulse-overview.json`
- Dashboard usage notes: `dashboards/README.md`
- Runbook used by shipped alerts: `docs/runbooks/error-budget-burn.md`

## Important behavior notes

- Timeout-budget propagation forwards the remaining budget but does **not** set client socket/read timeouts for you.
- Cardinality overflow events are exported as `pulse_cardinality_overflow_total` tagged by `meter` and `tag_key`.
- `/actuator/pulse/runtime` reports top overflowing `(meter, tag_key)` pairs to speed up incident triage.
- `pulse.exception-handler.enabled=true` installs a global fallback advice with lowest precedence so app-specific handlers can override it.
- Environment tagging defaults to `app.env` (falls back to `unknown-env`).

## Requirements

- Java 21+
- Spring Boot 4.0+
- Log4j2 runtime (starter is configured for Log4j2)

## Adoption path

- **Day 1**: add dependency, set OTLP endpoint, verify `/actuator/pulse`.
- **Week 1**: import dashboard + alerts, add `SpanEvents.emit(...)` at key business moments.
- **Month 1**: tune sampling/cardinality/timeout budgets using production telemetry.

## Project status

This project is actively developed. See:

- `CHANGELOG.md` for current changes
- `CONTRIBUTING.md` for local workflow and standards
- `SECURITY.md` for vulnerability reporting and support policy

## License

MIT
