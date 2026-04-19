package io.github.arun0009.pulse.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.function.Supplier;

/**
 * Convenience facade over {@link MeterRegistry} for teams that want a one-liner for the common
 * cases (count, time, gauge).
 *
 * <p>For business observability, prefer {@link io.github.arun0009.pulse.events.SpanEvents} — one
 * call there gives you the span attribute, the structured log line, and the bounded counter all at
 * once. {@code BusinessMetrics} is here for the non-event metric needs (queue depth gauges, infra
 * timers, etc.).
 */
public class BusinessMetrics {

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void count(String name, String... tags) {
        Counter.builder(name).tags(tags).register(registry).increment();
    }

    public void count(String name, double amount, String... tags) {
        Counter.builder(name).tags(tags).register(registry).increment(amount);
    }

    public <T> T timed(String name, String[] tags, Supplier<T> work) {
        return Timer.builder(name).tags(tags).register(registry).record(work);
    }

    public void timed(String name, String[] tags, Runnable work) {
        Timer.builder(name).tags(tags).register(registry).record(work);
    }

    public void gauge(String name, Supplier<Number> source, String... tags) {
        Gauge.builder(name, source).tags(tags).register(registry);
    }

    public MeterRegistry registry() {
        return registry;
    }
}
