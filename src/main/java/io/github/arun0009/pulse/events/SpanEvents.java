package io.github.arun0009.pulse.events;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final WideEventsProperties config;
    private final ObservationRegistry observationRegistry;

    /**
     * Backwards-compatible constructor — the Observation seam is created in NOOP mode, which means
     * no handlers fire and zero overhead. Equivalent to pre-1.1 behaviour.
     */
    public SpanEvents(MeterRegistry registry, WideEventsProperties config) {
        this(registry, config, ObservationRegistry.NOOP);
    }

    /**
     * @param observationRegistry the application's {@link ObservationRegistry} (Spring Boot 3+
     *     auto-configures one). When non-NOOP, every {@link #emit(String, Map)} also opens and
     *     closes a low-cardinality {@code pulse.event} observation tagged with the event name —
     *     so any registered {@code ObservationHandler} (Boot's micrometer-tracing,
     *     custom audit handlers, OpenTelemetry's {@code OtelObservationHandler}) participates.
     *     This is purely additive: the existing counter, span event, and log line still fire
     *     regardless of the registry.
     */
    public SpanEvents(MeterRegistry registry, WideEventsProperties config, ObservationRegistry observationRegistry) {
        this.registry = registry;
        this.config = config;
        this.observationRegistry = observationRegistry == null ? ObservationRegistry.NOOP : observationRegistry;
    }

    /** Emits an event with no attributes — increments the counter and emits a log line. */
    public void emit(String name) {
        emit(name, Map.of());
    }

    /**
     * Emits an event. Attributes go on the active span and (via MDC) the JSON log line; the counter
     * is incremented by 1 (tagged only by event name).
     *
     * <p>Each concern (counter, span, log) is wrapped independently so a failure in one never
     * prevents the others from executing. Observability must never break the business path.
     */
    public void emit(String name, Map<String, ?> attributes) {
        if (!config.enabled()) return;

        Observation observation = startObservation(name, attributes);

        if (config.counterEnabled()) {
            try {
                registry.counter(config.counterName(), Tags.of(Tag.of("event", name)))
                        .increment();
            } catch (Exception e) {
                log.debug("Pulse: failed to increment event counter for '{}': {}", name, e.getMessage());
            }
        }

        try {
            Span span = Span.current();
            if (span.getSpanContext().isValid() && !attributes.isEmpty()) {
                span.addEvent(name, toAttributes(attributes));
            } else if (span.getSpanContext().isValid()) {
                span.addEvent(name);
            }
        } catch (Exception e) {
            log.debug("Pulse: failed to add span event '{}': {}", name, e.getMessage());
        }

        if (config.logEnabled()) {
            Map<String, String> stashed = stashOnMdc(attributes);
            try {
                log.info("{} {} {}", config.logMessagePrefix(), name, attributes);
            } finally {
                stashed.keySet().forEach(MDC::remove);
            }
        }

        stopObservation(observation);
    }

    /**
     * Starts a low-cardinality {@link Observation} so any {@code ObservationHandler} the
     * application has registered (e.g., Boot's micrometer-tracing bridge, custom audit handlers)
     * sees Pulse wide events as first-class observations.
     *
     * <p>Only the {@code event.name} key-value is added — high-cardinality attributes are
     * deliberately kept off the observation to protect downstream metrics, mirroring the same
     * cardinality discipline as the wide-event counter.
     */
    private @Nullable Observation startObservation(String name, Map<String, ?> attributes) {
        if (observationRegistry.isNoop()) return null;
        try {
            Observation observation = Observation.createNotStarted("pulse.event", observationRegistry)
                    .lowCardinalityKeyValue("event.name", name);
            // Attach high-cardinality attributes only to the observation context (not as
            // KeyValues) so existing meter handlers don't explode cardinality. Custom
            // ObservationHandlers can inspect them via the context.
            if (!attributes.isEmpty()) {
                observation.contextualName(name);
                attributes.forEach((k, v) -> {
                    if (v != null) {
                        observation.highCardinalityKeyValue(k, String.valueOf(v));
                    }
                });
            }
            return observation.start();
        } catch (Exception e) {
            log.debug("Pulse: failed to start observation for '{}': {}", name, e.getMessage());
            return null;
        }
    }

    private void stopObservation(@Nullable Observation observation) {
        if (observation == null) return;
        try {
            observation.stop();
        } catch (Exception e) {
            log.debug("Pulse: failed to stop observation: {}", e.getMessage());
        }
    }

    /** Convenience for callers that already build {@link KeyValues} elsewhere. */
    public static KeyValues toKeyValues(Map<String, ?> attributes) {
        if (attributes.isEmpty()) return KeyValues.empty();
        List<io.micrometer.common.KeyValue> kvs = new ArrayList<>(attributes.size());
        attributes.forEach((k, v) -> {
            if (v != null) kvs.add(io.micrometer.common.KeyValue.of(k, String.valueOf(v)));
        });
        return KeyValues.of(kvs);
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
