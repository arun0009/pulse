# Kafka time-based consumer lag

> **Status:** Stable · **Config prefix:** `pulse.kafka.consumer` ·
> **Source:** [`PulseKafkaRecordInterceptor.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/propagation/PulseKafkaRecordInterceptor.java)

## Value prop

Offset lag is a vanity metric. *Time* lag is the SLO. A consumer that's
500k offsets behind a low-volume topic is fine; one that's 10k offsets
behind a high-volume topic might be 8 minutes behind real time. Pulse
measures the only number that matters.

## What it does

On every consumed record, Pulse computes
`now() − record.timestamp()` and records it on the
`pulse.kafka.consumer.time_lag` gauge (registered with base unit
`seconds`, so Prometheus normalises it to
`pulse_kafka_consumer_time_lag_seconds`).

The shipped `PulseKafkaConsumerFallingBehind` alert fires above 5 minutes.

## Metrics emitted

| Metric | Type | Tags | Description |
|---|---|---|---|
| `pulse.kafka.consumer.time_lag` | Gauge (seconds) | `topic`, `consumer-group` | `now() − record.timestamp` on each consumed record |

## Configuration

```yaml
pulse:
  kafka:
    consumer:
      time-lag-enabled: true
```

!!! note "Expanded coverage coming"

    Full reference (per-partition tagging caveats, interaction with
    `RecordInterceptor` chaining) lands in a 1.0.x patch.
