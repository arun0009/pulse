# Background jobs

> **TL;DR.** Every `@Scheduled` job gets RED metrics, an in-flight gauge,
> and a health check that flips DOWN when a job hasn't succeeded for too
> long. Stuck nightly jobs page on-call instead of going unnoticed.

`@Scheduled` jobs are the silent killers of Spring services. They run on a
private thread, log to the same place as everything else, and the first sign
that one has been failing for a week is when a downstream system breaks
because the nightly reconciliation never ran.

**Pulse gives every job RED metrics, an in-flight gauge, and a health
indicator** that flips DOWN when a job hasn't succeeded inside a
configurable grace period — so the on-call sees a stuck job before its
absence breaks something else.

## What you get

```promql
sum by (job) (rate(pulse_jobs_executions_total{outcome="failure"}[1h]))
```

A failing job lights up here long before its silence is felt downstream.

The `/actuator/health/jobs` indicator flips DOWN when any job hasn't
succeeded inside its grace period — so Kubernetes (or whatever consumes the
health probe) can surface it as a real failure, not a buried log line.

## Turn it on

Nothing. Every `@Scheduled` job is observed automatically. ShedLock-managed
jobs work too — the decorator wraps the `Runnable` *before* the scheduler
sees it.

To tune the grace period (default 1 hour):

```yaml
pulse:
  jobs:
    failure-grace-period: 6h
```

## What it adds

| Metric | Type | Tags |
| --- | --- | --- |
| `pulse.jobs.executions` | Counter | `job`, `outcome` (`success` / `failure`) |
| `pulse.jobs.duration` | Timer | `job`, `outcome` |
| `pulse.jobs.in_flight` | Gauge | `job` (overrun detector) |

Plus the `/actuator/health/jobs` indicator.

## When to skip it

If you already monitor your scheduled jobs through Quartz JMX or a
job-runner-specific dashboard:

```yaml
pulse:
  jobs:
    enabled: false
```

---

**Source:** [`io.github.arun0009.pulse.jobs`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/jobs) ·
**Status:** Stable since 1.0.0
