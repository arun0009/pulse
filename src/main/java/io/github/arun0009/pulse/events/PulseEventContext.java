package io.github.arun0009.pulse.events;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed {@link Observation.Context} used by {@link SpanEvents}. Carries the event name and the
 * caller-supplied attribute map so any {@link io.micrometer.observation.ObservationHandler
 * ObservationHandler} the application registers — Pulse's built-in counter / span / logging
 * handlers, plus user-authored handlers (Sentry breadcrumbs, audit log, business-event router) —
 * can consume the same shape via {@code instanceof PulseEventContext}.
 *
 * <p>The context is intentionally minimal. Anything that needs additional state (timing, request
 * id, route) can read it off MDC or {@code RequestContextHolder} during handler invocation; we
 * keep the context payload narrow so the wide-event hot path stays allocation-light.
 */
public final class PulseEventContext extends Observation.Context {

    private final String eventName;
    private final Map<String, Object> attributes;

    public PulseEventContext(String eventName, Map<String, ?> attributes) {
        this.eventName = eventName;
        this.attributes = attributes == null ? Map.of() : copyOf(attributes);
        setName("pulse.event");
        setContextualName(eventName);
        // Publish event.name as a low-cardinality KeyValue so generic ObservationHandlers
        // (meter handlers, tracing bridge, custom audit handlers) see it via the standard
        // Context API without needing to downcast to PulseEventContext.
        addLowCardinalityKeyValue(KeyValue.of("event.name", eventName));
        // Event attributes are high-cardinality by design — the counter-handler deliberately
        // ignores them; the span/log handlers consume them directly. Publishing them here means
        // third-party ObservationHandlers (e.g. OtelObservationHandler) also see them.
        this.attributes.forEach((k, v) -> {
            if (v != null) addHighCardinalityKeyValue(KeyValue.of(k, String.valueOf(v)));
        });
    }

    /** Logical name of the event, e.g. {@code "order.placed"}. Never {@code null}. */
    public String eventName() {
        return eventName;
    }

    /**
     * Caller-supplied attributes for the event. The map is a defensive copy and is therefore
     * safe to iterate from any handler without external synchronisation. Never {@code null};
     * may be empty.
     */
    public Map<String, Object> attributes() {
        return attributes;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyOf(Map<String, ?> source) {
        Map<String, @Nullable Object> copy = new LinkedHashMap<>(source.size());
        source.forEach((k, v) -> {
            if (k != null) copy.put(k, v);
        });
        return (Map<String, Object>) (Map<?, ?>) copy;
    }
}
