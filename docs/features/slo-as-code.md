# SLO-as-code

> **TL;DR.** Declare your SLO once in YAML; Pulse renders the multi-burn
> Prometheus alert *and* exposes live compliance on `/actuator/pulse`.
> No more dashboard ↔ alert ↔ wiki drift.

You declare your SLOs in a wiki, you build a dashboard for them, you write
PrometheusRule alerts for them, and the three drift apart inside a quarter.
The dashboard says 99.92%, the alert says you're burning fast, and nobody
trusts either.

**Pulse makes the YAML the single source of truth.** You declare the
objective once. Pulse renders it as a multi-window, multi-burn-rate
`PrometheusRule` (the Google SRE workbook pattern) *and* projects live
compliance into the actuator. Same source, three views, one number.

## What you get

Declare the objective:

```yaml
pulse:
  slo:
    objectives:
      - name: orders-availability
        sli: availability
        target: 0.999
      - name: orders-latency
        sli: latency
        target: 0.95
        threshold: 500ms
```

Get the rule file ready to apply:

```bash
curl -s localhost:8080/actuator/pulse/slo | kubectl apply -f -
```

Get live in-process compliance for spot-checks without a Prometheus round-trip:

```bash
curl -s localhost:8080/actuator/pulse/runtime | jq .slo
```

## Turn it on

It's on by default; you just declare objectives. Two SLI flavours ship out
of the box:

| `sli` | Definition |
| --- | --- |
| `availability` | Fraction of non-5xx responses on `http.server.requests` |
| `latency` | Fraction of responses below `threshold` |

## What it adds

| Endpoint | Returns |
| --- | --- |
| `/actuator/pulse/slo` | Full `PrometheusRule` YAML (recording rules + multi-window burn-rate alerts) |
| `/actuator/pulse/runtime` | Live in-process compliance projection per objective |

## When to skip it

If you maintain SLO YAML separately (Sloth, Pyrra, hand-written rules) and
don't want a parallel source:

```yaml
pulse:
  slo:
    enabled: false
```

When disabled, `/actuator/pulse/slo` returns a sentinel
`# SLO subsystem disabled` payload so dashboards don't break with a 500.

---

**Source:** [`SloRuleGenerator.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/slo/SloRuleGenerator.java) ·
**Runbook:** [Error-budget burn](../runbooks/error-budget-burn.md) ·
**Status:** Stable since 1.0.0
