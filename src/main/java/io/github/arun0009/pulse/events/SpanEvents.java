package io.github.arun0009.pulse.events;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Pulse wide-event API. One call attaches typed attributes to the active span, emits a
 * structured INFO log line carrying the same attributes (so they land in the JSON layout and remain
 * joinable by trace id), and increments a single bounded counter that you can SLO against.
 *
 * <p>The point: business observability that is normally fragmented across three storage layers
 * (logs, metrics, traces) becomes a single event you write once and query anywhere.
 *
 * <pre>
 * &#064;Autowired SpanEvents events;
 *
 * events.emit("order.placed",
 *     Map.of("orderId", id,
 *            "amount", amount,
 *            "currency", "USD",
 *            "tier", customer.tier()));
 * </pre>
 *
 * <p>Cardinality is preserved on the span and log (rich) but flattened on the counter (only the
 * event name is tagged) so you cannot accidentally explode the metrics backend by attaching a
 * high-cardinality value like {@code orderId}.
 */
public final class SpanEvents {

    private static final Logger log = LoggerFactory.getLogger("pulse.events");

    private final MeterRegistry registry;
    private final PulseProperties.WideEvents config;

    public SpanEvents(MeterRegistry registry, PulseProperties.WideEvents config) {
        this.registry = registry;
        this.config = config;
    }

    /** Emits an event with no attributes — increments the counter and emits a log line. */
    public void emit(String name) {
        emit(name, Map.of());
    }

    /**
     * Emits an event. Attributes go on the active span and (via MDC) the JSON log line; the counter
     * is incremented by 1 (tagged only by event name).
     */
    public void emit(String name, Map<String, ?> attributes) {
        if (!config.enabled()) return;

        if (config.counterEnabled()) {
            registry.counter(config.counterName(), Tags.of(Tag.of("event", name)))
                    .increment();
        }

        Span span = Span.current();
        if (span.getSpanContext().isValid() && !attributes.isEmpty()) {
            span.addEvent(name, toAttributes(attributes));
        } else if (span.getSpanContext().isValid()) {
            span.addEvent(name);
        }

        if (config.logEnabled()) {
            Map<String, String> stashed = stashOnMdc(attributes);
            try {
                log.info("{} {} {}", config.logMessagePrefix(), name, attributes);
            } finally {
                stashed.keySet().forEach(MDC::remove);
            }
        }
    }

    private static Attributes toAttributes(Map<String, ?> source) {
        AttributesBuilder builder = Attributes.builder();
        source.forEach((k, v) -> putTyped(builder, k, v));
        return builder.build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void putTyped(AttributesBuilder builder, String key, Object value) {
        switch (value) {
            case null -> {
                /* OTel attributes do not accept null values */
            }
            case String s -> builder.put(AttributeKey.stringKey(key), s);
            case Boolean b -> builder.put(AttributeKey.booleanKey(key), b);
            case Long l -> builder.put(AttributeKey.longKey(key), l);
            case Integer i -> builder.put(AttributeKey.longKey(key), i.longValue());
            case Double d -> builder.put(AttributeKey.doubleKey(key), d);
            case Float f -> builder.put(AttributeKey.doubleKey(key), f.doubleValue());
            default -> builder.put(AttributeKey.stringKey(key), String.valueOf(value));
        }
    }

    private static Map<String, String> stashOnMdc(Map<String, ?> attributes) {
        Map<String, String> added = new LinkedHashMap<>();
        attributes.forEach((k, v) -> {
            if (v != null && MDC.get(k) == null) {
                MDC.put(k, String.valueOf(v));
                added.put(k, String.valueOf(v));
            }
        });
        return added;
    }
}
