# Runbook — Inbound Trace Context Missing

**Alert**: `PulseTraceContextMissing`
**Severity**: warning
**Pages**: no — engineer-driven cleanup

## TL;DR

Pulse's `TraceGuardFilter` is observing inbound HTTP requests that arrive **without** a valid
W3C `traceparent` (or legacy B3 `X-B3-TraceId`) header. Some upstream caller is not propagating
trace context — the trace will show as a stub or won't join with the upstream span at all.

## Why this matters

The number-one reason traces "look broken" in production is not the OTel SDK — it's a single
service in the chain that drops or strips context. Pulse counts these requests so the gap is
visible instead of silently producing orphan traces.

## Triage

```promql
# Which routes are arriving without context?
topk(10,
  sum by (route) (
    rate(pulse_trace_missing_total[5m])
  )
)
```

```promql
# Health ratio — what fraction of inbound requests are propagated correctly?
sum(rate(pulse_trace_received_total[5m]))
/
(
  sum(rate(pulse_trace_received_total[5m]))
  + sum(rate(pulse_trace_missing_total[5m]))
)
```

A healthy service should sit at >0.99. Anything lower means at least one upstream is dropping
context.

## Find the bad caller

The request reaches Pulse with no `traceparent`, so the originating identity is not in the
trace itself. To attribute:

1. Add a transient log enrichment to the offending route:
   ```java
   @GetMapping("/foo")
   ResponseEntity<?> foo(@RequestHeader HttpHeaders headers) {
       log.info("inbound headers (sampled): {}", headers);
       …
   }
   ```
2. Look at `User-Agent`, `X-Forwarded-For`, and any service-mesh-injected headers (e.g.
   `x-envoy-original-path`, `x-istio-attributes`).
3. Compare with your service-mesh / gateway logs around the timestamp of the alert.

Common culprits:

| Pattern                                | Fix                                                            |
|----------------------------------------|----------------------------------------------------------------|
| Internal traffic from a non-Pulse app  | Add Pulse (or the OTel agent / Spring Cloud Sleuth) to that app|
| Health checks                          | Add the path to `pulse.trace-guard.exclude-path-prefixes`     |
| Synthetic monitors / load tests        | Have the synthetics emit `traceparent` per W3C spec            |
| Browser SPA                            | Use the OTel browser SDK or accept the orphan-frontend trace   |

## Don't blanket-exclude

If you find yourself adding most of your endpoints to the exclude list, the alert is doing its
job — you have a real propagation gap. The correct response is to fix the caller, not to silence
the metric.

## Hard mode (block on missing context)

In environments where every inbound call **must** have context (mesh-only ingress, e.g.), set:

```yaml
pulse:
  trace-guard:
    fail-on-missing: true
```

Pulse will reject requests without context with a 500 — useful as a CI / staging gate to catch
regressions before production.

## See also

- `src/main/java/io/github/arun0009/pulse/core/TraceGuardFilter.java` — implementation
- `dashboards/grafana/pulse-overview.json` — Trace propagation panel
