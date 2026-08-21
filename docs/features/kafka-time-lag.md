# Kafka time-based consumer lag

> **TL;DR.** `now() − record.timestamp()` per consumed record, in seconds.
> The only consumer-lag number that matters when your SLO is freshness.
> Optionally skip records older than a max age — that is the
> **event-freshness** clock, not the HTTP timeout budget.

Offset lag is a vanity metric. A consumer that's 500k offsets behind a
low-volume topic is fine; one that's 10k offsets behind a high-volume topic
might be eight minutes behind real time. Time lag is the SLO. Offset lag is
not.

**Pulse measures `now() − record.timestamp()` on every consumed record** and
exposes it as a single metric in seconds — the only number that matters when
your SLO is freshness.

## Two clocks (do not mix them)

The HTTP caller's 2s timeout budget is an **RPC deadline**. Kafka often
outlives that request: `202 Accepted`, then "charge the card" on a
listener. Pulse does **not** skip `@KafkaListener` methods because
`Pulse-Timeout-Ms` expired. The RPC deadline is reconstructed on consume
from `Pulse-Timeout-Deadline-Ms` so *downstream HTTP from the listener*
still sees the original caller's remaining time (or zero) — it does not
drop the business event.

Event freshness is a separate, opt-in decision: "this record is too old to
act on." See skip-stale below. Full RPC vs event split:
[timeout-budget](timeout-budget.md#two-clocks).

## What you get

```promql
max by (topic, group) (pulse_kafka_consumer_time_lag_seconds) > 300
```

Any consumer falling more than five minutes behind real time, regardless of
topic volume. The shipped alert (`PulseKafkaConsumerFallingBehind`) fires
here.

## Turn it on

Nothing. On by default whenever Pulse's Kafka [record interceptor](context-propagation.md)
is registered (also default).

## Skip stale records (opt-in)

When the business rule is "ignore events older than N" — a price tick, a
cache invalidate, a location ping — enable skip. Spring Kafka treats a
`RecordInterceptor` returning `null` as drop (the listener is not invoked;
the offset still advances with your ack mode).

```yaml
pulse:
  kafka:
    skip-stale-records: true
    skip-stale-max-age: 5m
```

Records with no Kafka timestamp (`timestamp <= 0`) are never skipped.
Skipped records increment `pulse.kafka.stale_skipped{topic=…}`.

Do **not** enable this for "charge the card" / "send the email" topics
unless you have an idempotent retry path for the dropped event. Time lag
still records on skipped records so you can see how far behind you are.

## What it adds

| Metric | Type | Tags | Meaning |
| --- | --- | --- | --- |
| `pulse.kafka.consumer.time_lag` | Gauge (seconds) | `group`, `topic`, `partition` | `now() − record.timestamp` on the most recent consumed record (per partition) |
| `pulse.kafka.stale_skipped` | Counter | `topic` | Records dropped because `skip-stale-records` is on and age exceeded `skip-stale-max-age` |

Prometheus normalises the gauge to `pulse_kafka_consumer_time_lag_seconds`.

## When to skip it

If you're already capturing time lag from a Kafka exporter or burrow:

```yaml
pulse:
  kafka:
    consumer-time-lag-enabled: false
```

---

**Source:** [`PulseKafkaRecordInterceptor.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/propagation/PulseKafkaRecordInterceptor.java) ·
**Status:** Stable since 1.0.0
