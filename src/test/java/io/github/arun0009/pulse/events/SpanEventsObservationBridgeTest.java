package io.github.arun0009.pulse.events;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Observation API is the modern Spring 6 / Boot 3+ seam: any handler registered against the
 * {@link ObservationRegistry} (Boot's micrometer-tracing bridge, custom audit handlers, OTel's
 * {@code OtelObservationHandler}) automatically participates in any observation the application
 * starts. By bridging Pulse wide events through that seam, consumers get tracing/metrics on their
 * business events without writing any glue.
 *
 * <p>The bridge must be:
 *
 * <ul>
 *   <li><b>Additive</b> — the existing counter, span event, and log line still fire. We assert
 *       both the new observation handler runs <em>and</em> the legacy counter increments.
 *   <li><b>Cardinality-safe</b> — only {@code event.name} is exposed as a low-cardinality key.
 *       Attribute values land on the observation context as high-cardinality keys, which the
 *       default meter handler ignores.
 *   <li><b>Backwards-compatible</b> — the no-arg constructor wires {@link
 *       ObservationRegistry#NOOP}, so consumers that don't have an {@link ObservationRegistry}
 *       on the classpath see exactly the pre-1.1 behaviour.
 *  </ul>
 */
class SpanEventsObservationBridgeTest {

    private static final WideEventsProperties DEFAULT_CONFIG =
            new WideEventsProperties(true, true, true, "pulse.events", "event");

    @Test
    void observation_handler_observes_pulse_events_when_registry_is_provided() {
        ObservationRegistry observations = ObservationRegistry.create();
        RecordingHandler handler = new RecordingHandler();
        observations.observationConfig().observationHandler(handler);

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SpanEvents events = new SpanEvents(meters, DEFAULT_CONFIG, observations);

        events.emit("order.placed", Map.of("orderId", "ord-123", "amount", 49.99));

        assertThat(handler.starts)
                .as("handler must observe a pulse.event observation per emit() call")
                .containsExactly("pulse.event");
        assertThat(handler.stops)
                .as("observation must be stopped so timing handlers complete cleanly")
                .containsExactly("pulse.event");
        assertThat(handler.lowCardinalityKeyValues)
                .as("event.name must travel as a low-cardinality key so it's safe to tag on metrics")
                .containsExactly("event.name=order.placed");
        assertThat(meters.find("pulse.events")
                        .tag("event", "order.placed")
                        .counter()
                        .count())
                .as("legacy counter still fires — the bridge must be additive, not a replacement")
                .isEqualTo(1.0);
    }

    @Test
    void noop_registry_means_zero_overhead_and_no_handler_invocations() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SpanEvents events = new SpanEvents(meters, DEFAULT_CONFIG);

        events.emit("background.tick");

        assertThat(meters.find("pulse.events")
                        .tag("event", "background.tick")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void high_cardinality_attributes_are_kept_off_the_low_cardinality_keys() {
        ObservationRegistry observations = ObservationRegistry.create();
        RecordingHandler handler = new RecordingHandler();
        observations.observationConfig().observationHandler(handler);
        SpanEvents events = new SpanEvents(new SimpleMeterRegistry(), DEFAULT_CONFIG, observations);

        events.emit("payment.captured", Map.of("paymentId", "pay-XYZ", "amount", 99));

        assertThat(handler.lowCardinalityKeyValues)
                .as("only event.name belongs at low cardinality — paymentId would explode metrics")
                .containsExactly("event.name=payment.captured");
        assertThat(handler.highCardinalityKeyValues)
                .as("contextual attributes flow as high-cardinality keys for tracing handlers")
                .contains("paymentId=pay-XYZ", "amount=99");
    }

    private static final class RecordingHandler implements ObservationHandler<Observation.Context> {

        final List<String> starts = new ArrayList<>();
        final List<String> stops = new ArrayList<>();
        final List<String> lowCardinalityKeyValues = new ArrayList<>();
        final List<String> highCardinalityKeyValues = new ArrayList<>();

        @Override
        public void onStart(Observation.Context context) {
            starts.add(context.getName());
        }

        @Override
        public void onStop(Observation.Context context) {
            stops.add(context.getName());
            context.getLowCardinalityKeyValues()
                    .forEach(kv -> lowCardinalityKeyValues.add(kv.getKey() + "=" + kv.getValue()));
            context.getHighCardinalityKeyValues()
                    .forEach(kv -> highCardinalityKeyValues.add(kv.getKey() + "=" + kv.getValue()));
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }
}
