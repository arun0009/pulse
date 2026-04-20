package io.github.arun0009.pulse.events;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Pulse wide-event API. One call attaches typed attributes to the active span, emits a
 * structured INFO log line carrying the same attributes (so they land in the JSON layout and
 * remain joinable by trace id), and increments a single bounded counter that you can SLO against.
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
 *
 * <h2>Architecture (Pulse 2.0)</h2>
 *
 * <p>{@code emit()} is a single five-line pipeline: build a {@link PulseEventContext}, start an
 * {@link Observation}, fan out to handlers, stop the observation. There is exactly one place
 * where signals attach to the wide event — every transport (counter, span event, structured log)
 * is an {@link ObservationHandler}{@code <PulseEventContext>}, registered as a bean and
 * therefore individually toggleable via {@code pulse.wide-events.*} or replaceable via
 * {@code @ConditionalOnMissingBean}. Applications can plug in their own handlers (Sentry
 * breadcrumb, audit-log to Kafka, business-event router) by publishing an
 * {@code ObservationHandler<PulseEventContext>} bean — Spring Boot's
 * {@code ObservationAutoConfiguration} attaches it to the application's {@link
 * ObservationRegistry} automatically.
 *
 * <p>When the application's {@code ObservationRegistry} is {@link ObservationRegistry#NOOP NOOP}
 * (typically in unit tests), {@code emit()} bypasses the Observation pipeline and invokes the
 * built-in handlers directly so wide events still produce signals. This preserves the
 * "wide events always work" guarantee from earlier Pulse releases without forcing every test
 * to stand up a real {@code ObservationRegistry}.
 */
public final class SpanEvents {

    private final WideEventsProperties config;
    private final ObservationRegistry observationRegistry;
    private final List<ObservationHandler<PulseEventContext>> builtInHandlers;

    /**
     * Single primary constructor.
     *
     * @param config the {@code pulse.wide-events.*} bound properties.
     * @param observationRegistry the application's registry. When non-NOOP, every {@link
     *     #emit(String, Map)} flows through the standard {@link Observation} pipeline so any
     *     ObservationHandler the application has registered (Pulse's built-in handlers, custom
     *     handlers, Boot's tracing handler) participates.
     * @param builtInHandlers the Pulse-shipped handlers that produce the counter, span event,
     *     and structured log line. Used as a NOOP-registry fallback so wide events still emit
     *     in unit tests that pass {@link ObservationRegistry#NOOP}.
     */
    public SpanEvents(
            WideEventsProperties config,
            ObservationRegistry observationRegistry,
            List<ObservationHandler<PulseEventContext>> builtInHandlers) {
        this.config = config;
        this.observationRegistry = observationRegistry == null ? ObservationRegistry.NOOP : observationRegistry;
        this.builtInHandlers = builtInHandlers == null ? List.of() : List.copyOf(builtInHandlers);
    }

    /** Emits an event with no attributes — increments the counter and emits a log line. */
    public void emit(String name) {
        emit(name, Map.of());
    }

    /**
     * Emits an event. Attributes go on the active span and (via MDC) the JSON log line; the
     * counter is incremented by 1 (tagged only by event name).
     */
    public void emit(String name, Map<String, ?> attributes) {
        if (!config.enabled()) return;

        PulseEventContext context = new PulseEventContext(name, attributes);
        Observation observation = Observation.createNotStarted("pulse.event", () -> context, observationRegistry);
        observation.start();
        if (observation.isNoop()) {
            // Fallback path: no handlers fire via the Observation pipeline because the registry is
            // NOOP. Invoke the built-ins directly so wide events still produce signals — this is
            // the contract several unit tests rely on (they pass ObservationRegistry.NOOP).
            invokeBuiltInsDirectly(context);
        }
        observation.stop();
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

    private void invokeBuiltInsDirectly(PulseEventContext context) {
        for (ObservationHandler<PulseEventContext> handler : builtInHandlers) {
            try {
                handler.onStart(context);
            } catch (RuntimeException ignored) {
                /* defensive — handlers must not break the business path */
            }
        }
        for (ObservationHandler<PulseEventContext> handler : builtInHandlers) {
            try {
                handler.onStop(context);
            } catch (RuntimeException ignored) {
                /* defensive — handlers must not break the business path */
            }
        }
    }
}
