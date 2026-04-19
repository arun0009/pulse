# Pulse showcase

Runnable side-by-side proof of three production failure modes that take down
real systems — with and without Pulse on the edge service.

| # | Scenario              | Without Pulse                                 | With Pulse                                          |
|---|-----------------------|-----------------------------------------------|-----------------------------------------------------|
| 1 | Custom MDC across `@Async` | `tenantId=null` on the `@Async` worker line   | `tenantId=acme-corp` preserved across the thread hop |
| 2 | Cardinality explosion | 13 raw `userId` tag values → 13 time series   | Capped at 10; the rest bucket to a synthetic `OVERFLOW` tag |
| 3 | Timeout cascade       | Caller-perceived **~5,000 ms** (full downstream sleep) | Caller-perceived **~500 ms** — propagated `Pulse-Timeout-Ms` honored |

> Spring Boot 4 propagates the OTel `traceId` across `@Async` natively, so that
> alone is no longer a Pulse differentiator. What Pulse uniquely propagates is
> **your** correlation context — tenantId, requestId, userId, and any other
> header you list under `pulse.context`.

## Quick start (90 seconds)

```bash
cd examples/showcase
make all
```

That runs:

1. `mvn install` for Pulse (one-shot, ~30s)
2. `mvn package` for both demo apps
3. `docker compose up -d` — three containers: `edge-with-pulse`, `edge-without-pulse`, `downstream`
4. `./scripts/demo.sh` — exercises all three scenarios against both edges and prints the log diff
5. `docker compose logs` hints for further inspection

## Sample output (real, from this repo)

```
[1/3] Custom MDC propagation across @Async
with-pulse:    [edge] entry — tenantId=acme-corp requestId=req-001 userId=alice
               [edge] async work — tenantId=acme-corp requestId=req-001 userId=alice
without-pulse: [edge] entry — tenantId=null requestId=null userId=null
               [edge] async work — tenantId=null requestId=null userId=null

[2/3] Cardinality firewall (13 unique userIds)
with-pulse    final: {"distinctSeries":11, ...}   ← 10 + 1 OVERFLOW bucket
without-pulse final: {"distinctSeries":13, ...}   ← unbounded growth

[3/3] Timeout-budget cascade (caller deadline = 500 ms)
with-pulse    caller-perceived: 550 ms  (downstream: "honored caller's deadline; gave up after 356ms")
without-pulse caller-perceived: 5,101 ms (downstream: "no Pulse-Timeout-Ms from caller — falling back to full 5000ms work")
```

## Inspect

After `make all`, browse the live dashboard:

| Where | What to look at |
|---|---|
| http://localhost:3000/d/pulse-overview *(admin / admin)* | The Pulse Service Overview dashboard, pre-loaded against your demo traffic |
| http://localhost:9090/alerts | Pulse multi-window burn-rate SLO alerts evaluating in real time |
| http://localhost:8080/actuator/pulse | Pulse self-diagnostics (every active subsystem) |
| http://localhost:8080/actuator/prometheus | Raw Pulse metrics (all common tags + cardinality firewall already applied) |
| `docker compose logs otel-collector` | Real OTLP traces and logs flowing from the demo apps |

```bash
# All distinct userId tag values in the with-pulse registry (look for OVERFLOW)
curl -s http://localhost:8080/actuator/metrics/orders.placed | jq '.availableTags'

# Counter that increments every time a downstream call hits exhausted budget
curl -s http://localhost:8080/actuator/metrics/pulse.timeout_budget.exhausted | jq .
```

## What's running

```
                        OTLP (traces, logs, metrics)
   ┌─────────────────────┬─────────────────────────┬──────────────┐
   │                     │                         │              │
┌──┴──────────────┐  ┌───┴────────────────┐  ┌─────┴────────┐     │
│ edge-with-pulse │  │ edge-without-pulse │  │  downstream  │     │
│      :8080      │  │       :8081        │  │     :8090    │     │
│  (Pulse fully   │  │  (Pulse beans      │  │              │     │
│   enabled)      │  │   disabled via     │  │              │     │
│                 │  │   profile)         │  │              │     │
└──────┬──────────┘  └────────────────────┘  └──────────────┘     │
       │   propagates Pulse-Timeout-Ms,                           │
       │   Pulse-Tenant-Id, X-Request-ID, X-User-ID               ▼
       ▼                                                  ┌──────────────────┐
   downstream call                                        │  otel-collector  │
                                                          │   :4317 (gRPC)   │
                          /actuator/prometheus            │   :4318 (HTTP)   │
                                  ▲                       └──────────────────┘
                                  │
                          ┌───────┴────────┐
                          │   prometheus   │  ──── alerts ──── /etc/prometheus/rules/
                          │     :9090      │                    pulse-slo-alerts.yaml
                          └───────┬────────┘
                                  │
                          ┌───────┴────────┐
                          │    grafana     │  ←  pre-provisioned datasource + dashboard
                          │     :3000      │
                          └────────────────┘
```

The downstream is itself running Pulse with `pulse.timeout-budget.default-budget: 0s`
so the demo can show the *raw* cascade — in a real production downstream you'd
keep the default safety net on.

## Cleanup

```bash
make down
```
