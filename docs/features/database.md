# Database (N+1 and slow query)

> **TL;DR.** Statements-per-request counter + slow-query span events,
> sourced from the Spring 6.2 DB Observation. Catches N+1s in PromQL
> instead of in a tail-latency post-mortem.

The N+1 query bug is the most common Spring/JPA performance footgun, and
it's invisible to most observability stacks because each individual
statement looks fine — it's the *count per request* that matters.

**Pulse counts statements per request.** When one route accidentally fires
fifty queries, you see it in a metric instead of in a tail-latency
investigation.

## What you get

```promql
sum by (endpoint) (rate(pulse_db_n_plus_one_suspect_total[5m])) > 0
```

Any non-zero result is a route that crossed the threshold (default 50
statements per request) at least once in the window. The shipped alert
points straight at the endpoint.

You also get the per-request distribution itself, so you can spot drift over
time:

```promql
histogram_quantile(0.95,
  sum by (endpoint, le) (rate(pulse_db_statements_per_request_bucket[5m])))
```

## Turn it on

Nothing — automatic when Hibernate ORM is on the classpath.

To raise or lower the threshold:

```yaml
pulse:
  db:
    n-plus-one-threshold: 30
```

## What it adds

| Metric / signal | Meaning |
| --- | --- |
| `pulse.db.statements_per_request` | Distribution summary, prepared statements per request |
| `pulse.db.n_plus_one.suspect` (tag `endpoint`) | Counter — fires when the threshold trips |
| Span event `pulse.db.n_plus_one.suspect` | Marks the offending request in the trace |
| `WARN` log (one-time per endpoint per minute) | Surfaces it without scraping Prometheus |

Slow queries flow through Hibernate's built-in `org.hibernate.SQL_SLOW`
logger, automatically correlated with the trace by Pulse's [structured
logs](structured-logs.md) layout.

## When to skip it

If you already use `p6spy`, Datadog APM, or another query-counting layer:

```yaml
pulse:
  db:
    enabled: false
```

## Conditional gating

To suppress N+1 detection for known-chatty admin endpoints (bulk imports,
batch reconciliation jobs that legitimately fire hundreds of statements)
without raising the global threshold, use
[`enabled-when`](conditional-features.md):

```yaml
pulse:
  db:
    enabled-when:
      path-excludes:
        - /admin/bulk-import
        - /admin/reconcile
```

Requests excluded by the matcher don't open the per-request statement
scope at all — no `pulse.db.*` metrics, no warning, no trace event.

---

**Source:** [`io.github.arun0009.pulse.db`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/db) ·
**Status:** Stable since 1.0.0
