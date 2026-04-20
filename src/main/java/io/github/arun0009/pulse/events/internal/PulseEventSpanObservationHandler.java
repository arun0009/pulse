package io.github.arun0009.pulse.events.internal;

import io.github.arun0009.pulse.events.PulseEventContext;
import io.github.arun0009.pulse.tracing.internal.PulseSpans;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Attaches a span event with the event name to the currently-recording span (if any) and tags the
 * span with the wide-event attributes. Runs at observation start so the event lands at the moment
 * of emission rather than at scope close.
 *
 * <p><strong>Honest API limitation</strong>: Micrometer's {@link Span} interface has no
 * {@code event(name, attributes)} overload — the OTel-bridged implementation does, but exposing
 * it would require either a hard OTel SDK dependency or runtime reflection (both of which Pulse
 * 2.0 explicitly avoided in Phase 4b). As a pragmatic compromise the handler stamps the
 * attributes as span <em>tags</em> instead, so they remain queryable in any tracing backend
 * (Tempo, Jaeger, Zipkin) but are scoped to the parent span rather than the event itself.
 * Applications that want strict event-attribute scoping can swap this handler for an OTel-aware
 * one — that's the whole point of the {@link ObservationHandler} seam.
 */
public final class PulseEventSpanObservationHandler implements ObservationHandler<PulseEventContext> {

    private static final Logger log = LoggerFactory.getLogger(PulseEventSpanObservationHandler.class);

    private final Tracer tracer;

    public PulseEventSpanObservationHandler(Tracer tracer) {
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
    }

    @Override
    public void onStart(PulseEventContext context) {
        try {
            Span span = PulseSpans.recordable(tracer);
            if (span == null) return;
            span.event(context.eventName());
            context.attributes().forEach((k, v) -> tagOnSpan(span, k, v));
        } catch (Exception e) {
            log.debug("Pulse: failed to add span event '{}': {}", context.eventName(), e.getMessage());
        }
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof PulseEventContext;
    }

    private static void tagOnSpan(Span span, String key, @Nullable Object value) {
        switch (value) {
            case null -> {
                /* tracing API has no null-tag concept */
            }
            case String s -> span.tag(key, s);
            case Boolean b -> span.tag(key, b);
            case Long l -> span.tag(key, l);
            case Integer i -> span.tag(key, i.longValue());
            case Double d -> span.tag(key, d);
            case Float f -> span.tag(key, f.doubleValue());
            default -> span.tag(key, String.valueOf(value));
        }
    }
}
