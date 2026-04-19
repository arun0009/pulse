# Context propagation (`@Async` / `@Scheduled` / Kafka)

> **Status:** Stable · **Config prefix:** `pulse.async`, `pulse.scheduling`,
> `pulse.kafka` ·
> **Source:** [`PulseTaskDecorator.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/async/PulseTaskDecorator.java),
> [`ExecutorConfiguration.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/async/ExecutorConfiguration.java),
> [`PulseKafkaRecordInterceptor.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/propagation/PulseKafkaRecordInterceptor.java) ·
> **Runbook:** [Trace context missing](../runbooks/trace-context-missing.md)

## Value prop

Spring is full of "fire and forget" surfaces — `@Async` methods, `@Scheduled`
jobs, custom executors, Kafka listeners — and every one is a place where
your `traceId`, `requestId`, `userId`, tenant, and timeout budget silently
disappear because someone forgot to call `MDC.getCopyOfContextMap()` and
restore it on the worker thread.

The result: you have a beautiful trace for the synchronous half of your
request and a black hole for everything that happened asynchronously.

Pulse fixes this for every Spring-managed thread automatically. You don't
write the boilerplate, you don't remember to wrap futures, you don't decide
which fields to copy.

## What it does

### Spring `TaskExecutor` and `TaskScheduler`

`ExecutorConfiguration` registers a `BeanPostProcessor` that wraps every
`TaskExecutor` and `TaskScheduler` bean with a `PulseTaskDecorator`. The
decorator captures **on submit** the full triple — MDC map, OTel `Context`,
Pulse thread-locals (`TimeoutBudget`, `RequestPriority`, `Tenant`) — and
restores it on the worker thread, then clears in `finally`.

```java
@Async
public CompletableFuture<Order> submit(Order order) {
    log.info("placing order");   // traceId, requestId, userId all present
    return CompletableFuture.completedFuture(order);
}

@Scheduled(fixedDelay = 60_000)
public void reconcile() {
    log.info("reconciling");     // traceId is the scheduler's, not null
}
```

This works for `@Async` methods, `@Scheduled` jobs, anything submitted to a
Spring-managed `Executor`, and reactor `Schedulers` configured via Spring's
`Schedulers.onScheduleHook`.

### Kafka

`PulseKafkaRecordInterceptor` is composed with any user-configured
`RecordInterceptor` so the order is: Pulse → user → handler. Before the
listener method runs, Pulse:

- Extracts the trace context from the record headers using the configured
  OTel propagator.
- Restores MDC entries (`traceId`, `requestId`, `tenant`, `priority`).
- Restores the timeout budget (if present) so the listener can read
  `TimeoutBudget.current()`.
- Clears everything in `finally`.

`PulseKafkaProducerInterceptor` does the inverse on the producer side:
every `ProducerRecord` gets the current trace, request, tenant, and
remaining-budget headers stamped on it before it leaves the broker
boundary.

## Reactor / WebFlux

If you use Reactor on top of WebFlux, `PulseTaskDecorator`'s context capture
also feeds OTel's `Context.taskWrapping`, so anything scheduled on a
Pulse-decorated `Scheduler` keeps the OTel `Context` parented correctly.

For pure reactive request flows, no decoration is needed — the OTel
`Context` already follows the reactor `Context` chain. Pulse only fills the
gap between Spring's threading model and OTel's.

## Things that *don't* automatically propagate

These are deliberately left for you, because Pulse cannot know what you
intended:

- **Bare `new Thread(...)`** — Pulse does not patch raw threads. Wrap your
  `Runnable` in `PulseTaskDecorator.wrap(...)` if you must use them.
- **Third-party executors that bypass Spring** — e.g. a hand-built
  `ForkJoinPool` you never declare as a bean. Same fix as above.
- **`CompletableFuture.runAsync(...)` with no explicit executor** — uses the
  common pool. Either pass a Pulse-decorated executor or wrap the lambda.

For these cases, `PulseTaskDecorator.wrap(Runnable)` is one line and gives
you the same MDC + OTel + Pulse propagation manually.

## Metrics emitted

Context propagation itself is silent — there are no metrics, because the
right behaviour is "your existing log lines suddenly carry the right
correlation IDs." The complementary signals are:

- [Trace-context guard](trace-context-guard.md) — `pulse.trace.received` /
  `pulse.trace.missing`, which surfaces *missed* propagation per route.
- [Background-job observability](jobs.md) — RED metrics per `@Scheduled`
  job, which let you see the async thread's own behaviour.

## Configuration

```yaml
pulse:
  async:
    decorate-task-executors: true        # default
    decorate-task-schedulers: true       # default
  kafka:
    record-interceptor-enabled: true     # default
    producer-interceptor-enabled: true   # default
```

There's intentionally very little to configure here — the right answer is
"wrap everything, capture everything, restore everything, clear in finally,"
and that's the default.

## When to turn it off

Disable per-surface if you're already running a context-propagating
framework that does the same job (e.g., a custom `TaskDecorator` you
maintain):

```yaml
pulse:
  async:
    decorate-task-executors: false
```

You almost never want to disable Kafka propagation — it's the only way the
trace survives the broker boundary.
