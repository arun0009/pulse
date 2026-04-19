# Error Budget Burn Runbook

This runbook is used by `PulseHighErrorBudgetBurnFast` and `PulseHighErrorBudgetBurnSlow`.

## What this means

- `PulseHighErrorBudgetBurnFast`: severe incident; page immediately.
- `PulseHighErrorBudgetBurnSlow`: sustained degradation; create or escalate a ticket.

Both alerts are derived from `http_server_requests_seconds_count` and represent 5xx availability burn against a 99.9% SLO baseline.

## Immediate checks (first 10 minutes)

1. Confirm the blast radius in Grafana:
   - error rate by `application` and `env`
   - p99 latency
   - request volume changes
2. Check Pulse guardrail signals:
   - `pulse_timeout_budget_exhausted_total`
   - `pulse_cardinality_overflow_total`
3. Inspect recent deploys, config changes, and downstream health.

## Mitigation playbook

- Roll back the latest change if a deploy correlates.
- Shift or shed traffic if one region is unstable.
- Increase timeout budget only as a temporary measure and with explicit rollback.
- Disable a noisy integration path if it is causing widespread 5xx.

## Root cause checklist

- Was this caused by downstream latency, local saturation, or bad inputs?
- Did timeout-budget exhaustion spike before 5xx increased?
- Did cardinality overflow indicate instrumentation changes during the same period?

## Exit criteria

- Error burn returns below alert threshold for at least one evaluation window.
- Customer impact is assessed and communicated.
- Follow-up issue created with preventive actions and owner.
