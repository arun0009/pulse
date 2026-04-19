package io.github.arun0009.pulse.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.bulkhead.event.BulkheadOnCallRejectedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Observes a Resilience4j {@link BulkheadRegistry} and counts every rejected call. A
 * bulkhead-rejected call is a load-shedding event that should never silently disappear from
 * the production picture; even one rejection per minute means the bulkhead is sized too tight
 * for the load.
 *
 * <p>Emits {@code pulse.r4j.bulkhead.rejected_total{name}} and one structured WARN line per
 * rejection. Pulse's JSON layout adds {@code traceId} so the operator can pivot from the alert
 * to the rejected request's trace.
 */
public final class BulkheadObservation implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger("pulse.r4j.bulkhead");

    private final BulkheadRegistry registry;
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Boolean> wired = new ConcurrentHashMap<>();

    public BulkheadObservation(BulkheadRegistry registry, MeterRegistry meterRegistry) {
        this.registry = registry;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        registry.getAllBulkheads().forEach(this::attach);
        registry.getEventPublisher().onEntryAdded(event -> attach(event.getAddedEntry()));
    }

    private void attach(Bulkhead bulkhead) {
        if (wired.putIfAbsent(bulkhead.getName(), Boolean.TRUE) != null) return;
        bulkhead.getEventPublisher().onCallRejected(this::onRejected);
    }

    private void onRejected(BulkheadOnCallRejectedEvent event) {
        meterRegistry
                .counter("pulse.r4j.bulkhead.rejected_total", Tags.of("name", event.getBulkheadName()))
                .increment();

        Span span = Span.current();
        if (span.getSpanContext().isValid()) {
            span.addEvent(
                    "pulse.r4j.bulkhead.rejected",
                    Attributes.of(AttributeKey.stringKey("pulse.r4j.bulkhead.name"), event.getBulkheadName()));
        }

        log.warn("bulkhead {} rejected a call (capacity exhausted)", event.getBulkheadName());
    }

    int wiredCount() {
        return wired.size();
    }
}
