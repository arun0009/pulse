# SLO-as-code

> **Status:** Stable · **Config prefix:** `pulse.slo` ·
> **Source:** [`SloRuleGenerator.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/slo/SloRuleGenerator.java) ·
> **Runbook:** [Error-budget burn](../runbooks/error-budget-burn.md)

## Value prop

You declare your SLOs once in `application.yml`. Pulse renders them as a
multi-window, multi-burn-rate `PrometheusRule` (the Google SRE workbook
pattern) and projects live compliance into the actuator. No more "the
dashboard says 99.92% but the alert says we're burning fast" — there's one
source of truth.

## What it does

```yaml
pulse:
  slo:
    enabled: true
    objectives:
      - name: orders-availability
        sli: availability
        target: 0.999
      - name: orders-latency
        sli: latency
        target: 0.95
        threshold: 500ms
```

`/actuator/pulse/slo` returns a complete `PrometheusRule` YAML document —
recording rules + multi-window burn-rate alerts — ready to apply:

```bash
curl -s localhost:8080/actuator/pulse/slo | kubectl apply -f -
```

`/actuator/pulse/runtime` reports live in-process projection so you can
spot-check compliance without round-tripping through Prometheus.

Two SLI flavours ship out of the box:

- `availability` — fraction of non-5xx responses on `http.server.requests`
- `latency` — fraction of responses below `threshold`

## Gating

Both `SloRuleGenerator` and the live projector are gated by
`pulse.slo.enabled` (default `true`). When disabled,
`/actuator/pulse/slo` returns a sentinel `# SLO subsystem disabled` payload
so dashboards don't break with a 500.

!!! note "Expanded coverage coming"

    Full reference (custom SLI types via SPI, multi-window thresholds,
    recommended Grafana panel) lands in a 1.0.x patch.
