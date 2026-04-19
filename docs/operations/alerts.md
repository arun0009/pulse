# Alerts

Pulse ships a Prometheus alert rule file at
[`alerts/prometheus/pulse-slo-alerts.yaml`](https://github.com/arun0009/pulse/blob/main/alerts/prometheus/pulse-slo-alerts.yaml)
that you can drop into your Prometheus `rule_files` (or apply via
`PrometheusRule` if you run the Prometheus Operator).

## What ships

| Alert | Fires on | Runbook |
|---|---|---|
| `PulseCardinalityOverflow` | `rate(pulse_cardinality_overflow_total[5m]) > 0` | [Cardinality firewall overflow](../runbooks/cardinality-firewall-overflow.md) |
| `PulseTimeoutBudgetExhausted` | `rate(pulse_timeout_budget_exhausted_total[5m]) > 0` for 2m | [Timeout-budget exhausted](../runbooks/timeout-budget-exhausted.md) |
| `PulseTraceContextMissing` | Missing-trace ratio > 5% over 10m | [Trace context missing](../runbooks/trace-context-missing.md) |
| `PulseKafkaConsumerFallingBehind` | `pulse_kafka_consumer_time_lag_seconds > 300` for 5m | (TBD) |
| `PulseConfigDrift` | `count(distinct pulse_config_hash) by (application, env) > 1` | (TBD) |
| `PulseHikariCpSaturated` | Pool utilisation > 90% for 5m | [HikariCP saturation](../runbooks/hikaricp-saturation.md) |
| `PulseOtelExporterStale` | `pulse_otel_exporter_last_success_age_seconds > 300` | (TBD) |
| Per-SLO multi-window burn-rate alerts | Generated dynamically from `pulse.slo.objectives` — see [SLO-as-code](../features/slo-as-code.md) | [Error-budget burn](../runbooks/error-budget-burn.md) |

## Generating SLO alerts

The `/actuator/pulse/slo` endpoint emits a `PrometheusRule` document
containing recording rules + multi-window burn-rate alerts for every
objective declared in `pulse.slo.objectives`. Apply with:

```bash
curl -s localhost:8080/actuator/pulse/slo | kubectl apply -f -
```

See [SLO-as-code](../features/slo-as-code.md) for the YAML schema and
the underlying rule generation logic.
