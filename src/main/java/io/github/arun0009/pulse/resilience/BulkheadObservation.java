package io.github.arun0009.pulse.resilience;

import io.github.arun0009.pulse.tracing.internal.PulseSpans;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.bulkhead.event.BulkheadOnCallRejectedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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
 * <p>Emits {@code pulse.resilience.bulkhead.rejected{name}} (counter; Prometheus exposition adds
 * {@code _total}) and one structured WARN line per rejection. Pulse's JSON layout adds
 * {@code traceId} so the operator can pivot from the alert to the rejected request's trace.
 */
public final class BulkheadObservation implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger("pulse.resilience.bulkhead");

    private final BulkheadRegistry registry;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;
    private final ConcurrentMap<String, Boolean> wired = new ConcurrentHashMap<>();

    public BulkheadObservation(BulkheadRegistry registry, MeterRegistry meterRegistry) {
        this(registry, meterRegistry, Tracer.NOOP);
    }

    public BulkheadObservation(BulkheadRegistry registry, MeterRegistry meterRegistry, Tracer tracer) {
        this.registry = registry;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
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
                .counter("pulse.resilience.bulkhead.rejected", Tags.of("name", event.getBulkheadName()))
                .increment();

        Span span = PulseSpans.recordable(tracer);
        if (span != null) {
            // TODO(phase 4e): per-event attributes — Micrometer's Span has no addEvent(name, attrs)
            // overload, so the bulkhead name lands as a span tag instead of an event attribute.
            span.event("pulse.resilience.bulkhead.rejected");
            span.tag("pulse.resilience.bulkhead.name", event.getBulkheadName());
        }

        log.warn("bulkhead {} rejected a call (capacity exhausted)", event.getBulkheadName());
    }

    int wiredCount() {
        return wired.size();
    }
}
