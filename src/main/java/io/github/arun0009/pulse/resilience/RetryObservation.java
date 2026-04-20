package io.github.arun0009.pulse.resilience;

import io.github.arun0009.pulse.tracing.internal.PulseSpans;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.retry.event.RetryOnErrorEvent;
import io.github.resilience4j.retry.event.RetryOnRetryEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Observes a Resilience4j {@link RetryRegistry} and turns its events into Pulse signals.
 *
 * <p>Why retries deserve their own observation: a quietly-retrying client mask is one of the
 * most common production blind spots — the request <em>did</em> succeed, but the upstream
 * exhausted its budget on the third attempt. A counter alone is insufficient; the operator
 * needs the retry count <em>per parent span</em> to know which inbound request burned the
 * upstream budget.
 *
 * <p>Wired signals:
 *
 * <ul>
 *   <li>{@code pulse.resilience.retry.attempts{name}} — every retry attempt past the first.
 *       Counter; the Prometheus exposition adds {@code _total} automatically.
 *   <li>{@code pulse.resilience.retry.exhausted{name}} — incremented when retry gives up.
 *   <li>Span event {@code pulse.resilience.retry.attempt} on the active span every time a retry
 *       fires, carrying the attempt number — so a slow-trace investigation immediately shows
 *       "this trace took 4s because we retried 3 times" rather than just "the call took 4s".
 * </ul>
 */
public final class RetryObservation implements SmartInitializingSingleton {

    private final RetryRegistry registry;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;
    private final ConcurrentMap<String, Boolean> wired = new ConcurrentHashMap<>();

    public RetryObservation(RetryRegistry registry, MeterRegistry meterRegistry) {
        this(registry, meterRegistry, Tracer.NOOP);
    }

    public RetryObservation(RetryRegistry registry, MeterRegistry meterRegistry, Tracer tracer) {
        this.registry = registry;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
    }

    @Override
    public void afterSingletonsInstantiated() {
        registry.getAllRetries().forEach(this::attach);
        registry.getEventPublisher().onEntryAdded(event -> attach(event.getAddedEntry()));
    }

    private void attach(Retry retry) {
        if (wired.putIfAbsent(retry.getName(), Boolean.TRUE) != null) return;
        retry.getEventPublisher().onRetry(this::onRetry).onError(this::onError);
    }

    private void onRetry(RetryOnRetryEvent event) {
        meterRegistry
                .counter("pulse.resilience.retry.attempts", Tags.of("name", event.getName()))
                .increment();

        int depth = RetryDepthContext.increment();
        MDC.put(RetryDepthFilter.MDC_KEY, Integer.toString(depth));

        Span span = PulseSpans.recordable(tracer);
        if (span != null) {
            span.event("pulse.resilience.retry.attempt");
            span.tag("pulse.resilience.retry.name", event.getName());
            span.tag("pulse.resilience.retry.attempt_number", event.getNumberOfRetryAttempts());
            span.tag("pulse.retry.depth", depth);
        }
    }

    private void onError(RetryOnErrorEvent event) {
        meterRegistry
                .counter("pulse.resilience.retry.exhausted", Tags.of("name", event.getName()))
                .increment();
    }

    int wiredCount() {
        return wired.size();
    }
}
