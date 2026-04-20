package io.github.arun0009.pulse.events.internal;

import io.github.arun0009.pulse.events.PulseEventContext;
import io.github.arun0009.pulse.events.WideEventsProperties;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits the structured INFO log line for a wide event. Attributes are stashed onto MDC for the
 * duration of the log call so the JSON layout (or any MDC-aware encoder) materialises them as
 * top-level fields, then removed in a finally block to avoid leaking event attributes into
 * subsequent log statements on the same thread.
 *
 * <p>Existing MDC keys are preserved — if the application has already set {@code orderId} on
 * MDC for the request, the wide-event handler does not overwrite it. This matches the
 * "request context is sacred, events enrich it" discipline that the rest of Pulse follows.
 */
public final class PulseEventLoggingObservationHandler implements ObservationHandler<PulseEventContext> {

    private final Logger log;
    private final WideEventsProperties config;

    public PulseEventLoggingObservationHandler(WideEventsProperties config) {
        this(config, LoggerFactory.getLogger("pulse.events"));
    }

    /**
     * Test seam: lets unit tests inject a captured Logger so they can assert on the emitted log
     * line without standing up the full SLF4J pipeline.
     */
    PulseEventLoggingObservationHandler(WideEventsProperties config, Logger log) {
        this.config = config;
        this.log = log;
    }

    @Override
    public void onStop(PulseEventContext context) {
        if (!config.logEnabled()) return;
        Map<String, String> stashed = stashOnMdc(context.attributes());
        try {
            log.info("{} {} {}", config.logMessagePrefix(), context.eventName(), context.attributes());
        } finally {
            stashed.keySet().forEach(MDC::remove);
        }
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof PulseEventContext;
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
