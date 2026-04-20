# Fleet config-drift detection

In any non-trivial deployment, *some* pods are running the wrong
configuration — a stale ConfigMap, a partial deploy, a one-pod env-var
typo. The symptom is "p99 tail latency is up but I can't find the bad
pod." The cause: half the fleet has a different timeout, sample rate, or
flag than the other half.

**Pulse hashes the resolved configuration tree at startup**, so the
divergent pod is one PromQL query away.

## What you get

```promql
count(count by (hash, application, env) (pulse_config_hash)) > 1
```

If any service / environment combination has more than one distinct hash,
the fleet is split. The shipped alert (`PulseConfigDrift`) fires here, and
the `/actuator/pulse/config-hash` endpoint on each pod tells you which
specific keys differ.

## Turn it on

Nothing. On by default. Each pod publishes one time series:
`pulse.config.hash{hash="..."}` with value 1.

## What it adds

| Endpoint / metric | Meaning |
| --- | --- |
| `pulse.config.hash` (gauge, value 1) | One time series per distinct resolved-config hash |
| `/actuator/pulse/config-hash` | The hash plus the contributing keys (for drift forensics) |

The hash is **deterministic** across JVMs — same effective YAML always
produces the same hash, so two pods with the same configuration will always
collapse to the same time series.

## When to skip it

```yaml
pulse:
  fleet:
    config-hash-enabled: false
```

---

**Source:** [`io.github.arun0009.pulse.fleet`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/fleet) ·
**Status:** Stable since 1.0.0
