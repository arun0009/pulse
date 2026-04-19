# Fleet config-drift detection

> **Status:** Stable · **Config prefix:** `pulse.fleet` ·
> **Source:** [`io.github.arun0009.pulse.fleet`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/fleet)

## Value prop

In any non-trivial deployment, *some* pods are running the wrong
configuration — a stale ConfigMap, a partial deploy, a one-pod env-var
typo. The symptom is "p99 tail latency is up but I can't find the bad
pod." Pulse hashes the resolved configuration tree at startup, so the
divergent pod is one PromQL query away.

## What it does

`ConfigHasher` deterministically hashes the resolved `pulse.*`
configuration tree at startup. Pulse exposes:

- `pulse.config.hash{hash}` (gauge, value 1) — one time series per
  distinct hash
- `/actuator/pulse/config-hash` — JSON document with the hash and the
  contributing keys

The recording rule
`count(distinct pulse_config_hash) by (application, env) > 1`
fires `PulseConfigDrift`.

## Configuration

```yaml
pulse:
  fleet:
    config-hash-enabled: true
```

!!! note "Expanded coverage coming"

    Full reference (hash inputs and exclusions, recommended alert PromQL,
    integration with rolling-deploy windows) lands in a 1.0.x patch.
