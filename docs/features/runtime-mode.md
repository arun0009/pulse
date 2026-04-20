# Killswitch + dry-run mode

> **TL;DR.** A process-wide three-state lever Pulse consults at the start of
> every hot path. Flip via `POST /actuator/pulse/mode` to take a feature out
> of enforcement in seconds — no redeploy, no rollback PR.

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

Three modes, one knob:

| Mode | Trace-context guard | Cardinality firewall | Timeout-budget filter | Diagnostics still emitted? |
| --- | --- | --- | --- | --- |
| `ENFORCING` | rejects (if configured) | rewrites to `OVERFLOW` | installs deadline | ✓ |
| `DRY_RUN` | logs and counts, never rejects | counts overflow but lets value through | installs deadline | ✓ |
| `OFF` | short-circuits the filter | passes every tag through | skips baggage | (none — full killswitch) |

Read the live mode:

```bash
$ curl -s localhost:8080/actuator/pulse/mode
{ "mode": "ENFORCING" }
```

Flip it:

```bash
$ curl -s -X POST -H 'Content-Type: application/json' \
    localhost:8080/actuator/pulse/mode -d '{"value":"DRY_RUN"}'
{ "previous": "ENFORCING", "current": "DRY_RUN", "note": "Mode is in-memory; redeploy or reset via this endpoint to re-arm." }
```

## Turn it on

Nothing. The mode lever is always wired; the bean exists regardless of
property settings so flipping `OFF → ENFORCING` is just as easy as the
reverse.

To pin the initial mode at startup:

```yaml
pulse:
  runtime:
    mode: ENFORCING   # or DRY_RUN, or OFF
```

A common pattern: ship a new deployment in `DRY_RUN` for a day, watch
`pulse.cardinality.overflow` and `pulse.trace.missing` to see what *would
have* happened, then flip `ENFORCING` once dashboards confirm impact is
what you expect.

## What it adds

| Endpoint | Method | Body | Purpose |
| --- | --- | --- | --- |
| `/actuator/pulse/mode` | `GET` | — | Current mode |
| `/actuator/pulse/mode` | `POST` | `{"value":"DRY_RUN"}` | Set mode |

The current mode is also surfaced in the top-level `/actuator/pulse`
snapshot, so on-call dashboards can render Pulse's posture without an
additional scrape.

## When to skip it

You can leave the mode pinned to `ENFORCING` and never touch the actuator —
that's how Pulse behaves in 1.0. The killswitch is an operational
escape-hatch, not a runtime cost. If you forbid actuator endpoints in
production entirely, the bean is still there; it just can't be flipped via
HTTP. Inject `PulseRuntimeMode` directly and call `set(...)` from your own
runbook automation.

## Under the hood

`PulseRuntimeMode` is a single `AtomicReference<Mode>`. Every enforcing
feature consults it as the very first short-circuit on its hot path —
`runtime.off()` skips the filter entirely, `runtime.dryRun()` flips
fingerprint logic so diagnostics still fire but enforcement does not. The
cost on the hot path is a single volatile read.

The mode is in-memory by design — no persistence, no rolling-state
gymnastics. A pod restart returns it to the property-configured value, so
any incident-time flip is automatically un-done by the next deploy.

---

**Source:** [`PulseRuntimeMode.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/runtime/PulseRuntimeMode.java) ·
**Status:** Stable since 1.1.0
