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
  exception-handler:
    enabled: false
```

When disabled, Pulse's `@RestControllerAdvice` is not registered at all
and your application's own exception handling takes over.

## Conditional gating

To skip Pulse-specific enrichment (fingerprint, span attribute, metric,
MDC) for *some* requests — typically internal admin tooling that has its
own error reporting — without disabling the handler, use
[`enabled-when`](conditional-features.md):

```yaml
pulse:
  exception-handler:
    enabled-when:
      path-excludes:
        - /admin
```

When the matcher rejects, callers still get a baseline RFC 7807
`ProblemDetail` (so the client doesn't see Spring's default error page),
but no fingerprint is computed, no metric is incremented, and no span
attribute is added.

## Custom fingerprint id (`ErrorFingerprintStrategy`)

Bring your own stable error id — Sentry's `event_id`, an in-house
bug-tracker key — by publishing a single bean. Pulse uses it everywhere
the fingerprint surfaces (response, span, MDC, metric tag).

```java
@Bean
ErrorFingerprintStrategy sentryFingerprint(SentryClient sentry) {
    return throwable -> {
        SentryEvent event = sentry.lastEventFor(throwable);
        return event != null
                ? event.getEventId()
                : ExceptionFingerprint.of(throwable);
    };
}
```

Implementations must be cheap (called on every unhandled exception),
side-effect-free, and must never throw. Strings up to ~32 chars work fine
on dashboards; longer is allowed but harder to read.

## Under the hood

Pulse registers a `@RestControllerAdvice` that catches every unhandled
exception. For each one:

1. Compute the fingerprint via the active `ErrorFingerprintStrategy` —
   the default hashes `exception.type` + top stack frames with SHA-256
   and truncates to ten hex characters (collision space `16¹⁰ ≈ 10¹²`).
2. Stamp it on MDC, the active span, the response body, and the
   `pulse.errors.unhandled` counter.

The default hash uses SHA-256 (not SHA-1, flagged early by CodeQL during
hardening) and the exception message is **not** in the input, so
per-record IDs and timestamps don't push the same bug into different
buckets.

---

**Source:** [`PulseExceptionHandler.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/exception/PulseExceptionHandler.java) ·
**Runbook:** [Error-budget burn](../runbooks/error-budget-burn.md) ·
**Status:** Stable since 1.0.0
