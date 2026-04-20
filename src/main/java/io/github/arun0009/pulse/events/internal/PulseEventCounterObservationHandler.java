package io.github.arun0009.pulse.events.internal;

import io.github.arun0009.pulse.events.PulseEventContext;
import io.github.arun0009.pulse.events.WideEventsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Increments the configured wide-event counter ({@code pulse.events} by default) tagged only by
 * the event name. The counter dimension is intentionally narrow so attaching a high-cardinality
 * value like {@code orderId} to a wide event cannot explode the metrics backend.
 *
 * <p>Implemented as an {@link ObservationHandler} so it is testable in isolation, replaceable by
 * users who want a different metric shape (e.g. a Timer with histogram for sub-event latencies),
 * and consistent with Pulse's "every signal is a handler bean" architecture.
 *
 * <p>Failures during increment are swallowed at DEBUG — observability must never break the
 * business path.
 */
public final class PulseEventCounterObservationHandler implements ObservationHandler<PulseEventContext> {

    private static final Logger log = LoggerFactory.getLogger(PulseEventCounterObservationHandler.class);

    private final MeterRegistry registry;
    private final WideEventsProperties config;

    public PulseEventCounterObservationHandler(MeterRegistry registry, WideEventsProperties config) {
        this.registry = registry;
        this.config = config;
    }

    @Override
    public void onStop(PulseEventContext context) {
        if (!config.counterEnabled()) return;
        try {
            registry.counter(config.counterName(), Tags.of(Tag.of("event", context.eventName())))
                    .increment();
        } catch (Exception e) {
            log.debug("Pulse: failed to increment event counter for '{}': {}", context.eventName(), e.getMessage());
        }
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof PulseEventContext;
    }
}
