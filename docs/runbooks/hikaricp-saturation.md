# Runbook — HikariCP Connection Pool Saturation

**Alerts**: `PulseHikariCpExhausted`, `PulseHikariCpConnectionAcquireSlow`, `PulseHikariCpConnectionTimeoutsRising`
**Severity**: page (exhausted, timeouts) / ticket (acquire-slow)

## TL;DR

The HikariCP connection pool is the choke point. Either (a) every connection is checked out
right now and threads are serializing on `getConnection()`, or (b) connections are timing
out before the pool can hand one out. Both translate to user-visible failures within seconds.

Triage in this order:

1. **Is a slow downstream dependency holding connections open?** (most common)
2. **Is there a connection leak in application code?**
3. **Is the pool simply undersized for current traffic?**

## What Pulse already did for you

- Charts active vs max utilization, pending acquires, p50/p95/p99 acquire latency, and
  acquire timeouts in the **Pulse → Saturation — HikariCP** dashboard row.
- Shipped three Prometheus alerts (page on exhaustion / timeouts, ticket on slow acquires).
- Tagged every panel by `pool` so multi-pool services (read-replica + primary) are
  separable.

Pulse does **not** add a new metric here — Spring Boot already publishes
`hikaricp.connections.*` via Micrometer when HikariCP is on the classpath. Pulse's value
is the pre-built dashboard, alerts, and this runbook.

## Diagnose

```bash
# Active vs max right now (per pool)
curl -s http://<host>/actuator/metrics/hikaricp.connections.active | jq
curl -s http://<host>/actuator/metrics/hikaricp.connections.max    | jq
curl -s http://<host>/actuator/metrics/hikaricp.connections.pending | jq
```

Pull a thread dump and look for threads parked in `HikariPool.getConnection`. The number
parked there equals `hikaricp_connections_pending` — those are your queued requests.

```bash
jstack $(pgrep -f your-app) | grep -A2 "HikariPool.getConnection"
```

## Fix patterns

| You see                                                  | Likely cause                                                | Fix                                                           |
|----------------------------------------------------------|-------------------------------------------------------------|---------------------------------------------------------------|
| Active = max, pending > 0, downstream DB latency rising  | A query (or DB) is slow                                     | Find the slow query — `JdbcTemplate` traces, DB slow log     |
| Active = max, downstream is fine                         | Connection leak — code paths returning early without close  | Audit `try-with-resources`, transaction boundaries           |
| Active < max but pending > 0                             | Pool sized correctly but connections trickling back         | Investigate `connectionTimeout`, network between app and DB  |
| Acquire p95 spiked, no leak, no slow query               | Traffic exceeded provisioned capacity                       | Bump `spring.datasource.hikari.maximum-pool-size`            |
| Timeouts rising                                          | Acquire is exceeding `connectionTimeout` (default 30s)     | All of the above, immediately                                 |

## Capacity rule of thumb

A reasonable starting point is `pool_size = (cpu_cores * 2) + effective_spindle_count`
(see [HikariCP's pool-sizing wiki](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)).
For a 4-core service against an SSD-backed DB, that's ~10. Raising past 20–30 rarely helps
unless the DB itself is the issue.

## Long-term

- Trace every JDBC call. Pulse's standard histograms cover `jdbc.query`; aggregate by
  query name to spot the slow one.
- If your service makes outbound HTTP calls inside a DB transaction, that's an anti-pattern
  — the connection is held for the duration of the HTTP round-trip.
- Set a sensible `idleTimeout` and `maxLifetime` so connections recycle and the pool can
  detect dead connections quickly.
