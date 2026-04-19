# Dashboards & local stack

Pulse ships a complete observability pipeline you can run with one
command, plus a 20-panel Grafana dashboard pre-provisioned against it.

## Local stack

```bash
docker compose -f deploy/local-stack/docker-compose.yml up -d
```

Brings up:

| Service | URL | What it does |
|---|---|---|
| **OTel Collector** | `localhost:4318` (HTTP) / `localhost:4317` (gRPC) | Receives OTLP, exports to backends. Configured with `memory_limiter`, `retry_on_failure`, `health_check`. |
| **Prometheus** | [`localhost:9090`](http://localhost:9090) | Scrapes metrics. Pre-loaded with Pulse alert rules. |
| **Grafana** | [`localhost:3000`](http://localhost:3000) (`admin` / `admin`) | Pre-provisioned with Prometheus + Jaeger + Loki datasources and the Pulse overview dashboard. |
| **Jaeger** | [`localhost:16686`](http://localhost:16686) | Trace UI. |
| **Loki** | `localhost:3100` (via Grafana) | Log aggregation. Grafana links `traceId` in logs directly to Jaeger traces. |

Point your service at `http://localhost:4318` and you're done. See
[`deploy/local-stack/README.md`](https://github.com/arun0009/pulse/blob/main/deploy/local-stack/README.md).

## Pulse overview dashboard

[`dashboards/grafana/pulse-overview.json`](https://github.com/arun0009/pulse/blob/main/dashboards/grafana/pulse-overview.json)
contains 20 panels covering:

- **Golden signals** — per-route RED for `http.server.requests`
- **Guardrails** — cardinality overflow, timeout-budget exhaustion,
  trace-context missing ratios
- **Error fingerprints** — top 10 by count over the last 24 h
- **Trace propagation** — `pulse.trace.received` vs `pulse.trace.missing`
  ratios per service
- **Dependencies** — caller-side RED per logical downstream
- **Background jobs** — RED + in-flight per `@Scheduled` job
- **Container memory** — headroom ratio with the warning threshold marked
- **Kafka time-lag** — per-topic time-based consumer lag
- **OTel exporter health** — last-success age, total exports

Import via Grafana's UI (Dashboards → Import → upload JSON), or let the
local stack provision it automatically.

## Failure-demo example

For a runnable end-to-end demo (with-Pulse vs without-Pulse showing the
same failures), see
[`examples/showcase/`](https://github.com/arun0009/pulse/tree/main/examples/showcase).
