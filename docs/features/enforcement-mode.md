# Dry-run / enforcement mode

> **TL;DR.** A process-wide enforce-vs-observe lever Pulse consults at the
> start of every guardrail's hot path. Flip via
> `POST /actuator/pulse/enforcement` to take a feature out of enforcement in
> seconds — no redeploy, no rollback PR.

The hardest part of adopting an opinionated observability starter is the
first time one of its guardrails fires under load. The trace-context guard
rejects a real request because a partner stripped a header. The cardinality
firewall buckets a tag the team thought was safe. In a normal stack the only
way to recover is to redeploy with `enabled: false`. That's hours, an
incident, and a credibility hit.

**Pulse runs every enforcing feature past a single global mode lever.** Flip
it via the actuator and the very next request sees the change. There's no
cached decision a feature has to invalidate.

## What you get

Two modes, one knob:

| Mode | Trace-context guard | Cardinality firewall | Diagnostics still emitted? |
| --- | --- | --- | --- |
| `ENFORCING` | rejects (if configured) | rewrites to `OVERFLOW` | ✓ |
| `DRY_RUN` | logs and counts, never rejects | counts overflow but lets value through | ✓ |

To take a feature out of the picture entirely, set its own
`pulse.<feature>.enabled=false`. That per-feature toggle is the right
granularity for incident response and is why Pulse intentionally does *not*
ship a third "OFF" mode here.

Read the live mode:

```bash
$ curl -s localhost:8080/actuator/pulse/enforcement
{ "mode": "ENFORCING" }
```

Flip it:

```bash
$ curl -s -X POST -H 'Content-Type: application/json' \
    localhost:8080/actuator/pulse/enforcement -d '{"value":"DRY_RUN"}'
{ "previous": "ENFORCING", "current": "DRY_RUN", "note": "Change is in-memory and per-process. Persist via pulse.enforcement.mode in application.yml." }
```

## Turn it on

Nothing. The mode lever is always wired; the bean exists regardless of
property settings so flipping `DRY_RUN → ENFORCING` is just as easy as the
reverse.

To pin the initial mode at startup:

```yaml
pulse:
  enforcement:
    mode: ENFORCING   # or DRY_RUN
```

A common pattern: ship a new deployment in `DRY_RUN` for a day, watch
`pulse.cardinality.overflow` and `pulse.trace.missing` to see what *would
have* happened, then flip `ENFORCING` once dashboards confirm impact is
what you expect. The shipped `pulse-canary` profile already pins
`DRY_RUN`, so adding `pulse-canary` to your active profiles on a small
slice of pods gets you this for free.

## What it adds

| Endpoint | Method | Body | Purpose |
| --- | --- | --- | --- |
| `/actuator/pulse/enforcement` | `GET` | — | Current mode |
| `/actuator/pulse/enforcement` | `POST` | `{"value":"DRY_RUN"}` | Set mode |

The current mode is also surfaced in the top-level `/actuator/pulse`
snapshot, so on-call dashboards can render Pulse's posture without an
additional scrape.

The legacy `/actuator/pulse/mode` segment from the 1.1 milestone is still
accepted as a deprecated alias and will be removed in a future minor
release. The same goes for the `pulse.runtime.mode` property, which is
auto-migrated at startup with a one-line WARN log.

## When to skip it

You can leave the mode pinned to `ENFORCING` and never touch the actuator —
that's how Pulse behaves in 1.0. The lever is an operational escape-hatch,
not a runtime cost. If you forbid actuator endpoints in production
entirely, the bean is still there; it just can't be flipped via HTTP.
Inject `PulseEnforcementMode` directly and call `set(...)` from your own
runbook automation.

## Under the hood

`PulseEnforcementMode` is a single `AtomicReference<Mode>`. Every enforcing
feature consults it as the very first short-circuit on its hot path —
`enforcement.dryRun()` flips the fingerprint logic so diagnostics still
fire but enforcement does not. The cost on the hot path is a single
volatile read.

The mode is in-memory by design — no persistence, no rolling-state
gymnastics. A pod restart returns it to the property-configured value, so
any incident-time flip is automatically un-done by the next deploy.

---

**Source:** [`PulseEnforcementMode.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/enforcement/PulseEnforcementMode.java) ·
**Status:** Stable since 1.1.0
