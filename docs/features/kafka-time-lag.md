# Kafka time-based consumer lag

> **TL;DR.** `now() − record.timestamp()` per consumed record, in seconds.
> The only consumer-lag number that matters when your SLO is freshness.

Offset lag is a vanity metric. A consumer that's 500k offsets behind a
low-volume topic is fine; one that's 10k offsets behind a high-volume topic
might be eight minutes behind real time. Time lag is the SLO. Offset lag is
not.

**Pulse measures `now() − record.timestamp()` on every consumed record** and
exposes it as a single metric in seconds — the only number that matters when
your SLO is freshness.

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

## What it adds

| Metric | Type | Tags | Meaning |
| --- | --- | --- | --- |
| `pulse.kafka.consumer.time_lag` | Gauge (seconds) | `group`, `topic`, `partition` | `now() − record.timestamp` on the most recent consumed record (per partition) |

Prometheus normalises this to `pulse_kafka_consumer_time_lag_seconds`.

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
