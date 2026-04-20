package io.github.arun0009.pulse.events;

import io.github.arun0009.pulse.events.internal.PulseEventCounterObservationHandler;
import io.github.arun0009.pulse.events.internal.PulseEventLoggingObservationHandler;
import io.github.arun0009.pulse.events.internal.PulseEventSpanObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
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
 *   <li><b>Additive</b> — the built-in counter / span / log handlers still fire. Custom handlers
 *       registered on the registry are invoked in <em>addition</em>, not instead.
 *   <li><b>Cardinality-safe</b> — only {@code event.name} is exposed as a low-cardinality key so
 *       it's safe to tag on metrics. High-cardinality attributes (orderId, paymentId) flow on the
 *       high-cardinality channel which generic meter handlers deliberately ignore.
 *   <li><b>NOOP-safe</b> — when the registry is {@link ObservationRegistry#NOOP}, the built-in
 *       handlers are invoked directly so wide events still produce signals. This preserves the
 *       "wide events always work" guarantee from earlier Pulse releases without forcing every
 *       caller to wire a real {@code ObservationRegistry}.
 * </ul>
 */
class SpanEventsObservationBridgeTest {

    private static final WideEventsProperties DEFAULT_CONFIG =
            new WideEventsProperties(true, true, true, "pulse.events", "event");

    @Test
    void observation_handler_observes_pulse_events_when_registry_is_provided() {
        // Production wiring: Spring Boot's ObservationAutoConfiguration attaches every
        // ObservationHandler bean (including Pulse's built-in counter/span/log handlers) to the
        // application's registry. We mirror that here by attaching the built-ins explicitly so
        // the test exercises the same code path production does.
        ObservationRegistry observations = ObservationRegistry.create();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RecordingHandler recorder = new RecordingHandler();
        observations.observationConfig().observationHandler(recorder);
        observations
                .observationConfig()
                .observationHandler(new PulseEventCounterObservationHandler(meters, DEFAULT_CONFIG));

        // Built-ins passed to the SpanEvents constructor are only used as the NOOP-fallback —
        // this assertion path exercises the non-NOOP pipeline, so we deliberately pass an empty
        // list to prove the counter fires via the ObservationHandler attached to the registry
        // (i.e., via production-shaped wiring) rather than via the fallback.
        SpanEvents events = new SpanEvents(DEFAULT_CONFIG, observations, List.of());

        events.emit("order.placed", Map.of("orderId", "ord-123", "amount", 49.99));

        assertThat(recorder.starts)
                .as("handler must observe a pulse.event observation per emit() call")
                .containsExactly("pulse.event");
        assertThat(recorder.stops)
                .as("observation must be stopped so timing handlers complete cleanly")
                .containsExactly("pulse.event");
        assertThat(recorder.lowCardinalityKeyValues)
                .as("event.name must travel as a low-cardinality key so it's safe to tag on metrics")
                .containsExactly("event.name=order.placed");
        assertThat(meters.find("pulse.events")
                        .tag("event", "order.placed")
                        .counter()
                        .count())
                .as("built-in counter handler fires additively via the Observation pipeline")
                .isEqualTo(1.0);
    }

    @Test
    void noop_registry_means_builtin_handlers_run_via_the_fallback_path() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SpanEvents events = new SpanEvents(DEFAULT_CONFIG, ObservationRegistry.NOOP, builtIns(meters, DEFAULT_CONFIG));

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
        SpanEvents events = new SpanEvents(DEFAULT_CONFIG, observations, List.of());

        events.emit("payment.captured", Map.of("paymentId", "pay-XYZ", "amount", 99));

        assertThat(handler.lowCardinalityKeyValues)
                .as("only event.name belongs at low cardinality — paymentId would explode metrics")
                .containsExactly("event.name=payment.captured");
        assertThat(handler.highCardinalityKeyValues)
                .as("contextual attributes flow as high-cardinality keys for tracing handlers")
                .contains("paymentId=pay-XYZ", "amount=99");
    }

    private static List<ObservationHandler<PulseEventContext>> builtIns(
            SimpleMeterRegistry registry, WideEventsProperties config) {
        return List.of(
                new PulseEventCounterObservationHandler(registry, config),
                new PulseEventSpanObservationHandler(Tracer.NOOP),
                new PulseEventLoggingObservationHandler(config));
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
