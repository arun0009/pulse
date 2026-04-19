# Background-job observability

> **Status:** Stable · **Config prefix:** `pulse.jobs` ·
> **Source:** [`io.github.arun0009.pulse.jobs`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/jobs)

## Value prop

`@Scheduled` jobs are the silent killer of Spring services. They run on a
private thread, log to the same place as everything else, and the first
sign that one has been failing for a week is when a downstream system
breaks because the nightly reconciliation never ran.

Pulse gives every job RED metrics, an in-flight gauge, and a health
indicator that flips DOWN when a job hasn't succeeded inside a configurable
grace period.

## What it does

For every observed job:

- `pulse.jobs.executions{job, outcome}` — counter of `success` / `failure`
- `pulse.jobs.duration{job, outcome}` — timer
- `pulse.jobs.in_flight{job}` — gauge (overrun detector)
- `jobs` health indicator — flips DOWN when a job hasn't succeeded inside
  `pulse.jobs.failure-grace-period` (default 1 hour)

ShedLock-managed jobs are observed automatically because the decorator
wraps the `Runnable` *before* the scheduler sees it.

## Configuration

```yaml
pulse:
  jobs:
    enabled: true
    failure-grace-period: 1h
    health-indicator-enabled: true
```

!!! note "Expanded coverage coming"

    Full reference (job naming rules, ShedLock integration notes, recommended
    alert PromQL) lands in a 1.0.x patch.
