# Pulse local observability stack

A one-command, reproducible observability stack covering all three pillars — metrics, traces,
and logs — to point any local Pulse-equipped Spring Boot app at. No manual provisioning required.

## What you get

- **OpenTelemetry Collector** — listens on `:4317` (gRPC) and `:4318` (HTTP), exports traces to
  Jaeger, metrics to Prometheus, and logs to Loki. Configured with `memory_limiter`,
  `retry_on_failure`, and `health_check` extension as a production reference.
- **Prometheus** — scrapes the Collector and any local app's `/actuator/prometheus`. Pre-loaded
  with Pulse's standalone alert rules.
- **Grafana** — pre-provisioned with Prometheus, Jaeger, and Loki as data sources, plus the
  Pulse overview dashboard. Loki-to-Jaeger link lets you jump from a log line to its trace.
- **Jaeger** — trace UI at `http://localhost:16686`.
- **Loki** — log aggregation backend. Query via Grafana Explore.

## Run

```bash
docker compose -f deploy/local-stack/docker-compose.yml up -d
```

Then point your Spring Boot app at the Collector:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
./mvnw spring-boot:run
```

| Service     | URL                                | Login          |
|-------------|------------------------------------|----------------|
| Grafana     | http://localhost:3000              | admin / admin  |
| Prometheus  | http://localhost:9090              | —              |
| Jaeger      | http://localhost:16686             | —              |
| Loki        | http://localhost:3100 (API only)   | (via Grafana)  |
| Collector   | http://localhost:4318 (HTTP OTLP)  | —              |

## Healthchecks

All services include Docker healthchecks. Verify the stack is healthy:

```bash
docker compose -f deploy/local-stack/docker-compose.yml ps
```

All services should show `healthy` in the `STATUS` column.

## Stop

```bash
docker compose -f deploy/local-stack/docker-compose.yml down -v
```

## See also

- `examples/showcase/` — runnable demo using this stack to exercise three production failure modes.
