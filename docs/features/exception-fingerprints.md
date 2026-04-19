# Stable exception fingerprints

> **Status:** Stable · **Config prefix:** `pulse.exception` ·
> **Source:** [`PulseExceptionHandler.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/exception/PulseExceptionHandler.java) ·
> **Runbook:** [Error-budget burn](../runbooks/error-budget-burn.md)

## Value prop

The same bug throws the same exception with a slightly different message
each time — *"Order 12345 not found"*, *"Order 67890 not found"*, *"Order
54321 not found"* — and your error tracker shows them as 50 distinct issues
because the message contains an ID. Triage takes 10× longer than it should.

Pulse hashes the **exception type plus top stack frames** (not the message)
into a stable, low-cardinality fingerprint. The same bug groups across
deploys, hosts, and message variants.

## What it does

`PulseExceptionHandler` is a `@RestControllerAdvice` registered on every
Spring app. For every unhandled exception that flows through it:

1. Compute `SHA-256(exception.type + top N stack frames)`.
2. Truncate the resulting hex to a stable 10-character fingerprint.
3. Surface it on **three** signals:

| Signal | Where | Field |
|---|---|---|
| HTTP response | RFC 7807 problem-detail body | `errorFingerprint` |
| Active span | OTel attribute | `error.fingerprint` |
| Metric | Counter `pulse.errors.unhandled` | Tag `fingerprint` |

The same fingerprint is also emitted on the structured log line, so a
`grep` across logs / Loki / your favourite log backend correlates with
spans and metrics with no extra glue.

## Sample response

```json
{
  "type": "urn:pulse:error:internal",
  "title": "Internal Server Error",
  "status": 500,
  "requestId": "9b8a...",
  "traceId": "4c1f...",
  "errorFingerprint": "a3f1c2d8e0"
}
```

The body follows [RFC 7807 Problem Details](https://datatracker.ietf.org/doc/html/rfc7807),
so existing Spring `@ExceptionHandler`s and clients that already understand
problem-detail responses keep working — Pulse just adds the
`errorFingerprint`, `requestId`, and `traceId` fields on top.

## Why SHA-256

The original prototype used SHA-1; CodeQL flagged it as a weak-hash usage,
so the hash was upgraded to SHA-256 (truncated to 10 hex chars for
display). The collision space at 10 chars is `16^10 ≈ 10^12`, more than
enough for fingerprint clustering across realistic error volumes.

## Metrics emitted

| Metric | Type | Tags | Description |
|---|---|---|---|
| `pulse.errors.unhandled` | Counter | `exception` (Java type), `fingerprint` (10 hex chars), `route` (matched pattern) | Every unhandled exception caught by Pulse's advice |

The `fingerprint` tag is naturally low-cardinality (one per real bug), so it
fits comfortably under the [cardinality firewall](cardinality-firewall.md)
default of 1000 distinct values per `(meter, tag)`.

## Diagnostics

The shipped Grafana dashboard includes a "Top 10 error fingerprints (last
24 h)" table, sorted by count, so triage starts with "fix the top 3" rather
than "scroll the error tracker."

## Configuration

```yaml
pulse:
  exception:
    enabled: true                        # default
    fingerprint-length: 10               # default — hex chars
    fingerprint-frames: 5                # default — top stack frames hashed
    include-fingerprint-in-response: true # default
    include-trace-id-in-response: true    # default
    include-request-id-in-response: true  # default
    sanitize-message: true               # default — runs LogSanitizer on the title
```

| Key | Type | Default | Notes |
|---|---|---|---|
| `enabled` | boolean | `true` | Master switch |
| `fingerprint-length` | int | `10` | Truncation length of the SHA-256 hex |
| `fingerprint-frames` | int | `5` | How many top frames go into the hash |
| `include-fingerprint-in-response` | boolean | `true` | Surface in the RFC 7807 body |
| `include-trace-id-in-response` | boolean | `true` | Surface `traceId` in the body |
| `include-request-id-in-response` | boolean | `true` | Surface `requestId` in the body |
| `sanitize-message` | boolean | `true` | Strip newlines/control chars from the response title |

## When to turn it off

Disable when you already run Sentry, Honeybadger, or another error
aggregator that produces its own grouping fingerprints, and you don't want
two competing schemes:

```yaml
pulse:
  exception:
    enabled: false
```

You can also keep it enabled but suppress the response fields if your API
contract requires a stricter problem-detail shape:

```yaml
pulse:
  exception:
    include-fingerprint-in-response: false
    include-trace-id-in-response: false
```

The metric and span attribute keep working either way.
