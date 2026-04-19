# Pulse — Dashboards & Alerts

Production-ready observability artifacts that ship **with** the library. Drop
them into your stack; the metrics they reference are the ones Pulse emits by
default.

## Grafana

| File | What it shows |
|---|---|
| [`grafana/pulse-overview.json`](grafana/pulse-overview.json) | Service overview: availability, p99, RPS, RED, wide-event rates, cardinality-firewall overflows, timeout-budget exhaustion. |

Import via Grafana UI: **Dashboards → New → Import → Upload JSON**, then pick
your Prometheus data source. Templated by `application` and `env` labels.

## Prometheus alerts

| File | What it covers |
|---|---|
| [`alerts/prometheus/pulse-slo-alerts.yaml`](../alerts/prometheus/pulse-slo-alerts.yaml) | Multi-window multi-burn-rate SLO alerts (Google SRE workbook pattern), cardinality-firewall overflow, timeout-budget exhaustion, p99 regression. |

Drop the file into your `prometheus.rules.d/` and reload — or wire it into
your Helm/Kustomize alertmanager bundle. Adjust the `0.001` literal (=99.9%
SLO) at the top of `pulse-slo-alerts.yaml` if your SLO differs.

## Designed to be edited

These are starting points, not stone tablets. Fork them per service, edit the
SLO target, add panels for your business metrics — that's the point. Everything
they reference is a stable Pulse-emitted meter:

- `http_server_requests_seconds_*` (Spring Boot / Micrometer standard)
- `pulse_events_total{event=...}` (the wide-event API)
- `pulse_timeout_budget_exhausted_total` (timeout-budget guardrail)
- `pulse_cardinality_overflow_total{meter=...,tag_key=...}` (cardinality firewall)
