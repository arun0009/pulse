# Runbook — Cardinality Firewall Overflow

**Alert**: `PulseCardinalityFirewallOverflow`
**Severity**: warning
**Pages**: no — quiet ticket

## TL;DR

The Pulse cardinality firewall is rewriting tag values to the `OVERFLOW` bucket because at least
one `(meter, tag-key)` pair has exceeded `pulse.cardinality.max-tag-values-per-meter`. Some service
is tagging with an unbounded value (a user id, request id, full URL, message id, etc.). The
firewall has prevented a metrics bill explosion; your job is to find and fix the offender so the
metric becomes useful again.

## What Pulse already did for you

- Capped the unique tag-value count at the configured limit (default 1000 per `(meter, tag)`).
- Bucketed every value beyond the cap to the literal string `OVERFLOW`.
- Incremented `pulse.cardinality.overflow{meter,tag_key}` once per rewritten event.
- Logged a single WARN line for the offending pair on first occurrence.

## Find the offender

```bash
# Top offending (meter, tag) pairs since process start
curl -s http://<host>/actuator/pulse/runtime | jq '.cardinalityFirewall.topOffenders'
```

The same data is rendered in the "Cardinality" panel of `/actuator/pulseui`.

## Fix patterns

| You see                          | Likely cause                                  | Fix                                                             |
|----------------------------------|-----------------------------------------------|-----------------------------------------------------------------|
| `tag_key=userId`                 | per-user counter                              | drop the tag, or bucket into `userType` / `tier`               |
| `tag_key=uri`                    | path with id segments (`/users/{id}`)         | use Spring's route template (`@RequestMapping` value), not raw URI |
| `tag_key=correlationId`          | request id leaked into a metric               | tags carry context, never identifiers — log it, don't tag it   |
| `tag_key=messageKey`             | unbounded Kafka message keys                  | drop the tag or bucket into `topic_name` / `partition_id`       |

## Code level

If the offender is your own metric:

```java
// BAD — userId is unbounded
Counter.builder("orders.placed").tag("userId", id).register(registry);

// GOOD — userType is a small, fixed enum
Counter.builder("orders.placed").tag("userType", user.tier()).register(registry);
```

If the offender is a Boot/Micrometer-managed meter (e.g. `http.server.requests`), the fix is a
`MeterFilter` that drops or buckets the offending tag.

## Don't roll back the firewall

It is tempting to raise `pulse.cardinality.max-tag-values-per-meter` to make the alert quiet. Don't.
A series count per `(meter, tag)` of >10k is the leading cause of metrics-backend OOMs in production.
The firewall is the seatbelt, not the speed limit — fix the bad tag.

## See also

- `dashboards/grafana/pulse-overview.json` — Cardinality panel
- `src/main/java/io/github/arun0009/pulse/guardrails/CardinalityFirewall.java` — implementation
