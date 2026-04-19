package io.github.arun0009.pulse.resilience;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.retry.event.RetryOnErrorEvent;
import io.github.resilience4j.retry.event.RetryOnRetryEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
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
 *   <li>{@code pulse.r4j.retry.attempts_total{name}} — every retry attempt past the first.
 *   <li>{@code pulse.r4j.retry.exhausted_total{name}} — incremented when retry gives up.
 *   <li>Span event {@code pulse.r4j.retry.attempt} on the active span every time a retry fires,
 *       carrying the attempt number — so a slow-trace investigation immediately shows "this
 *       trace took 4s because we retried 3 times" rather than just "the call took 4s".
 * </ul>
 */
public final class RetryObservation implements SmartInitializingSingleton {

    private final RetryRegistry registry;
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Boolean> wired = new ConcurrentHashMap<>();

    public RetryObservation(RetryRegistry registry, MeterRegistry meterRegistry) {
        this.registry = registry;
        this.meterRegistry = meterRegistry;
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
                .counter("pulse.r4j.retry.attempts_total", Tags.of("name", event.getName()))
                .increment();

        Span span = Span.current();
        SpanContext spanContext = span.getSpanContext();
        if (spanContext.isValid()) {
            span.addEvent("pulse.r4j.retry.attempt");
            span.setAttribute("pulse.r4j.retry.name", event.getName());
            span.setAttribute("pulse.r4j.retry.attempt_number", event.getNumberOfRetryAttempts());
        }
    }

    private void onError(RetryOnErrorEvent event) {
        meterRegistry
                .counter("pulse.r4j.retry.exhausted_total", Tags.of("name", event.getName()))
                .increment();
    }

    int wiredCount() {
        return wired.size();
    }
}
