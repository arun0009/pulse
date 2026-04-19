# Runbook — Timeout Budget Exhausted

**Alert**: `PulseTimeoutBudgetExhausted`
**Severity**: warning → page if sustained
**Pages**: yes if > 1% of requests for > 10m

## TL;DR

`pulse.timeout_budget.exhausted` is firing because outbound calls are leaving this service with
**zero remaining budget**. The upstream caller's deadline had already passed when this hop tried
to call its downstream — every subsequent call is wasted work.

This is the canonical "retry storm precursor": a slow upstream tier accumulates retries, the call
chain runs over budget, downstream services do work that is guaranteed to be discarded, and
saturation cascades across the fleet.

## What Pulse already did for you

- Propagated the inbound `Pulse-Timeout-Ms` header onto OpenTelemetry baggage.
- Subtracted elapsed time at every outbound hop so the next service sees the real remaining budget.
- Counted exhausted outbound calls per `transport={resttemplate,restclient,webclient,okhttp,kafka}`.

## Triage

```bash
# Which transport(s) are exhausting?
curl -s http://<host>/actuator/prometheus | grep pulse_timeout_budget_exhausted
```

```promql
# Top services exhausting their inbound budget
topk(10,
  sum by (service) (
    rate(pulse_timeout_budget_exhausted_total[5m])
  )
)
```

```promql
# Compare against inbound traffic — are we losing >1% of calls?
sum(rate(pulse_timeout_budget_exhausted_total[5m]))
/
sum(rate(http_server_requests_seconds_count[5m]))
```

## Mitigation playbook

1. **Find the slow upstream**. The exhaustion is usually a symptom of one slow span in the trace.
   Pull a sample trace by request id from the alert:
   ```
   /actuator/pulse → recent errors → click traceId
   ```
2. **Check the upstream's SLO**. If the upstream is meeting its SLO, the inbound timeout is set
   too low for the traffic class — raise it via the caller's `Pulse-Timeout-Ms` header (or
   `pulse.timeout-budget.default-budget`).
3. **Cancel work, don't retry it**. If exhaustion is sustained, the right move is for the caller
   to fail fast (5xx with `Retry-After`) rather than retry. Verify your client retry config
   doesn't ignore `Retry-After`.
4. **Resilience4j circuit breaker**. Wrap the slow downstream in a circuit breaker keyed on the
   exhaustion rate. Pulse's metric is the natural breaker input.

## Permanent fix

| Symptom                                            | Fix                                                          |
|----------------------------------------------------|--------------------------------------------------------------|
| Sustained exhaustion on a specific upstream → me   | The upstream is not meeting its SLO. Open a ticket on them. |
| Bursty exhaustion correlated with deploys          | Cold-start GC pauses. Tune JVM, warm caches, longer probe.   |
| One hop's p99 dwarfs the others                    | That hop has a real latency bug. Profile it.                 |
| Even short paths exhaust                           | The inbound default-budget is too tight for the traffic.    |

## Don't disable the counter

If you're tempted to silence the alert, you've removed the only signal that prevents retry storms.
Raise the threshold or move the alert to ticket-only severity instead.

## See also

- `alerts/prometheus/pulse-slo-alerts.yaml` — alert definition
- `src/main/java/io/github/arun0009/pulse/guardrails/TimeoutBudgetFilter.java` — inbound logic
- `src/main/java/io/github/arun0009/pulse/guardrails/TimeoutBudgetOutbound.java` — outbound logic
