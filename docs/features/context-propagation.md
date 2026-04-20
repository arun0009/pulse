# Context propagation

`@Async` methods, `@Scheduled` jobs, custom executors, Kafka listeners — every
one of these is a place where your `traceId`, `requestId`, `userId`, tenant,
and timeout budget silently disappear. The result is half a beautiful trace
and half a black hole.

**Pulse fills the gap on every Spring-managed thread, automatically.** You
don't write the boilerplate, you don't remember to wrap futures, you don't
decide which fields to copy.

## What you get

The same log line you used to write — but the correlation IDs are now there:

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

Same thing happens on Kafka:

```java
@KafkaListener(topics = "orders")
public void onOrder(ConsumerRecord<String, Order> record) {
    log.info("received order");  // traceId restored from the record headers
}
```

In your trace UI, the async hop and the scheduled job now appear as proper
spans under the original request — not orphan spans with no parent.

## Turn it on

Nothing. It's on by default for every `TaskExecutor`, `TaskScheduler`, and
Kafka listener Spring registers.

The only configurable surface is per-source on/off:

```yaml
pulse:
  async:
    decorate-task-executors: true     # default
    decorate-task-schedulers: true    # default
  kafka:
    record-interceptor-enabled: true  # default
    producer-interceptor-enabled: true # default
```

For raw threads or third-party executors that bypass Spring (Pulse cannot
reach those automatically), wrap manually — one line:

```java
executor.submit(PulseTaskDecorator.wrap(() -> doWork()));
```

## What it adds

Context propagation itself is silent — no metrics, no headers Pulse invents
on its own. The right behaviour is "your existing log lines now carry the
right correlation IDs and your existing spans now have the right parent."

The complementary signals you'll want to watch:

| Want to know | Look at |
| --- | --- |
| Are inbound requests carrying trace context? | [Trace-context guard](trace-context-guard.md) |
| How are my `@Scheduled` jobs behaving? | [Background jobs](jobs.md) |
| Did the timeout budget survive the async hop? | `timeout_remaining_ms` field on every log line |

## When you need to do something manually

Three cases Pulse cannot reach automatically — wrap the `Runnable` yourself:

- **Bare `new Thread(...)`.** Pulse does not patch raw threads.
- **Third-party executors not registered as Spring beans.** A hand-built
  `ForkJoinPool` you never declare, for example.
- **`CompletableFuture.runAsync(...)` with no explicit executor** — uses the
  common pool, which Pulse cannot decorate.

In all three cases, `PulseTaskDecorator.wrap(Runnable)` gives you the same
MDC + OTel + Pulse propagation manually.

## When to skip it

Disable per-source if you maintain your own `TaskDecorator` and don't want
two stacked:

```yaml
pulse:
  async:
    decorate-task-executors: false
```

You almost never want to disable Kafka propagation — it's the only way the
trace survives the broker boundary.

## Under the hood

Pulse registers a `BeanPostProcessor` that wraps every `TaskExecutor` and
`TaskScheduler` bean with a decorator. The decorator captures three things
**when the task is submitted** — the MDC map, the OTel `Context`, and Pulse
thread-locals (`TimeoutBudget`, `RequestPriority`, `Tenant`) — and restores
them on the worker thread, then clears in `finally`.

For Kafka, a `RecordInterceptor` runs before the listener. It extracts the
trace context from the record headers, restores MDC, restores the timeout
budget, and clears in `finally`. The producer side does the inverse: every
`ProducerRecord` gets the current trace, request, tenant, and remaining-budget
headers stamped on it.

For pure reactive WebFlux, no decoration is needed — the OTel `Context`
already follows the Reactor `Context` chain. Pulse only fills the gap between
Spring's threading model and OTel's.

---

**Source:** [`PulseTaskDecorator.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/async/PulseTaskDecorator.java) ·
[`ExecutorConfiguration.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/async/ExecutorConfiguration.java) ·
[`PulseKafkaRecordInterceptor.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/propagation/PulseKafkaRecordInterceptor.java) ·
**Runbook:** [Trace context missing](../runbooks/trace-context-missing.md) ·
**Status:** Stable since 1.0.0
