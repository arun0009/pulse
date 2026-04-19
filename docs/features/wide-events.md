# Wide-event API (`SpanEvents`)

> **Status:** Stable · **Config prefix:** `pulse.wide-events` ·
> **Source:** [`SpanEvents.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/events/SpanEvents.java)

## Value prop

Three signals (span event + structured log + bounded counter) are the
right shape for "something interesting happened in business code." Doing
all three by hand is verbose, easy to forget, and inconsistent across the
codebase.

## What it does

```java
events.emit("order.placed",
    "amount", "10",
    "currency", "USD");
```

In one call, this:

1. Attaches an event with the given attributes to the active span.
2. Emits a structured `INFO` log line with the same attributes.
3. Increments a bounded counter `pulse.events{event}` (tag = event name only;
   per-name cardinality is naturally bounded).

Cost: ~25 ns/op — JMH-measured, reproducible with `make bench`. Source
benchmark:
[`SpanEventsBenchmark.java`](https://github.com/arun0009/pulse/blob/main/src/test/java/io/github/arun0009/pulse/bench/SpanEventsBenchmark.java).

## Configuration

```yaml
pulse:
  wide-events:
    enabled: true
    counter-enabled: true
    log-enabled: true
    counter-name: pulse.events
    log-message-prefix: event
```

!!! note "Expanded coverage coming"

    Full reference (custom attribute serialisation, log-shape compatibility
    with the structured-log layout, recommended naming convention) lands in
    a 1.0.x patch.
