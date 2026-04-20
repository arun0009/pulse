# Stable exception fingerprints

The same bug throws the same exception with a slightly different message
each time — *"Order 12345 not found"*, *"Order 67890 not found"*, *"Order
54321 not found"* — and your error tracker shows them as fifty distinct
issues because the message contains an ID. Triage takes ten times longer
than it should.

**Pulse hashes the exception type plus its top stack frames into a stable,
ten-character fingerprint.** Same bug, same fingerprint, regardless of how
the message varies. Same fingerprint shows up on the HTTP response, the
active span, the metric, and the log line — so you can pivot from any one
to the others.

## What you get

The HTTP response (RFC 7807 problem-detail format) carries the fingerprint:

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

So does the metric, so triage starts with one query — *"top errors by
fingerprint over the last hour"* — instead of scrolling through the error
tracker:

```promql
topk(10, sum by (fingerprint, exception) (rate(pulse_errors_unhandled_total[1h])))
```

The shipped Grafana dashboard renders this as a "Top 10 error fingerprints"
table, sorted by count.

## Turn it on

Nothing. It's on by default with sensible defaults: ten hex characters per
fingerprint, top five stack frames hashed.

## What it adds

| Where | Field |
| --- | --- |
| HTTP response (RFC 7807) | `errorFingerprint`, `traceId`, `requestId` |
| Active OTel span | Attribute `error.fingerprint` |
| Metric `pulse.errors.unhandled` | Tag `fingerprint` (and `exception`, `route`) |
| Log line | Field `error.fingerprint` |

The `fingerprint` tag is naturally low-cardinality (one per real bug), so it
fits comfortably under the [cardinality firewall](cardinality-firewall.md)
default of 1000 distinct values per `(meter, tag)`.

## When to skip it

Disable when you already run Sentry, Honeybadger, or another error
aggregator that produces its own grouping fingerprints, and you don't want
two competing schemes:

```yaml
pulse:
  exception:
    enabled: false
```

You can also keep it on but suppress the response fields if your API
contract requires a stricter problem-detail shape:

```yaml
pulse:
  exception:
    include-fingerprint-in-response: false
    include-trace-id-in-response: false
```

The metric and the span attribute keep working either way.

## Under the hood

Pulse registers a `@RestControllerAdvice` that catches every unhandled
exception. For each one:

1. Compute `SHA-256(exception.type + top N stack frames)`.
2. Truncate the hex to ten characters — collision space is `16¹⁰ ≈ 10¹²`,
   plenty for fingerprint clustering across realistic error volumes.
3. Surface on the response, span, metric, and log.

The hash uses SHA-256 (not SHA-1) — flagged early by CodeQL during
hardening — and the message is **not** in the input, so per-record IDs and
timestamps don't push the same bug into different buckets.

### All the knobs

```yaml
pulse:
  exception:
    enabled: true                          # default
    fingerprint-length: 10                 # default — hex chars
    fingerprint-frames: 5                  # default — top stack frames hashed
    include-fingerprint-in-response: true  # default
    include-trace-id-in-response: true     # default
    include-request-id-in-response: true   # default
    sanitize-message: true                 # strip newlines / control chars
```

---

**Source:** [`PulseExceptionHandler.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/exception/PulseExceptionHandler.java) ·
**Runbook:** [Error-budget burn](../runbooks/error-budget-burn.md) ·
**Status:** Stable since 1.0.0
