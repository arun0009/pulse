# Wide-event API

> **TL;DR.** One call (`SpanEvents.emit("order.placed", attrs)`) emits a
> span event, a structured log, and a counter. Three signals, one line of
> code, consistent across the codebase.

When something interesting happens in business code — *order placed*,
*payment failed*, *user upgraded* — the right shape is three signals at
once: a span event so the trace shows it, a structured log so it's
greppable, and a counter so you can alert. Doing all three by hand is
verbose, easy to forget, and inconsistent across the codebase.

**Pulse gives you a single call that emits all three.**

## What you get

```java
events.emit("order.placed",
    "amount", "10",
    "currency", "USD");
```

In one ~25 ns call, that:

1. Attaches an event with the given attributes to the active span.
2. Emits a structured `INFO` log line with the same attributes.
3. Increments a bounded counter `pulse.events{event}` (tagged by event name
   only — naturally bounded cardinality).

So *"how many `order.placed` events fired in the last hour, broken down by
the trace where each one originated"* is a single PromQL + trace pivot
instead of a hand-rolled glue layer.

## Turn it on

Nothing — autowire `SpanEvents` (`@Autowired SpanEvents events`) and call
`events.emit(...)`.

## What it adds

| Signal | Where |
| --- | --- |
| Span event | Active OTel span |
| Log line (`INFO`) | Same OTel-aligned JSON as the rest of your logs |
| Counter | `pulse.events` (tag `event`) |

Cost: ~25 ns per call, JMH-measured. Reproducible with `make bench` —
source: [`SpanEventsBenchmark.java`](https://github.com/arun0009/pulse/blob/main/src/test/java/io/github/arun0009/pulse/bench/SpanEventsBenchmark.java).

## When you only want one of the three

Disable the signals you don't need (each defaults `true`):

```yaml
pulse:
  wide-events:
    counter-enabled: false   # don't emit a metric
    log-enabled: false       # don't emit a log line
```

Or rename the counter / log prefix to fit an existing convention:

```yaml
pulse:
  wide-events:
    counter-name: app.events
    log-message-prefix: biz.event
```

## When to skip it

```yaml
pulse:
  wide-events:
    enabled: false
```

## Extending via `ObservationHandler`

Every signal the wide-event API emits is an
`ObservationHandler<PulseEventContext>` bean — `PulseEventCounterObservationHandler`,
`PulseEventSpanObservationHandler`, `PulseEventLoggingObservationHandler`.
Publish your own handler bean and Spring Boot's
`ObservationAutoConfiguration` attaches it to the application's
`ObservationRegistry`, where Pulse emits every wide event:

```java
@Bean
ObservationHandler<PulseEventContext> sentryBreadcrumbHandler() {
    return new ObservationHandler<>() {
        @Override public void onStop(PulseEventContext ctx) {
            Sentry.addBreadcrumb(Breadcrumb.from(Map.of(
                "event", ctx.eventName(),
                "attrs", ctx.attributes())));
        }
        @Override public boolean supportsContext(Observation.Context c) {
            return c instanceof PulseEventContext;
        }
    };
}
```

Typical extension targets: Sentry breadcrumbs, audit-log to Kafka,
business-event router, Slack alert on high-severity events.

---

**Source:** [`SpanEvents.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/events/SpanEvents.java) ·
**Status:** Stable since 1.0.0
