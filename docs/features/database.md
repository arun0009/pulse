# Database (N+1, slow query)

> **Status:** Stable · **Config prefix:** `pulse.db` ·
> **Source:** [`io.github.arun0009.pulse.db`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/db)

## Value prop

The N+1 query bug is the most common Spring/JPA performance footgun, and
it's invisible to most observability stacks because each statement looks
fine — it's the *count per request* that matters. Pulse counts.

## What it does

When Hibernate ORM is on the classpath, Pulse registers a
`StatementInspector` plus a servlet filter that count prepared statements
per request:

- `pulse.db.statements_per_request` — distribution summary
- `pulse.db.n_plus_one.suspect{endpoint}` — counter
- Span event `pulse.db.n_plus_one.suspect`
- One-time `WARN` log when a request crosses
  `pulse.db.n-plus-one-threshold` (default 50)

Slow queries flow through Hibernate's built-in `org.hibernate.SQL_SLOW`
logger, automatically correlated with the trace by Pulse's JSON layout
(see [structured logs](structured-logs.md)).

## Configuration

```yaml
pulse:
  db:
    enabled: true
    n-plus-one-threshold: 50
```

!!! note "Expanded coverage coming"

    Full reference (per-endpoint suspect-rate alert, slow-query threshold
    wiring, JDBC vs JPA differentiation) lands in a 1.0.x patch.
