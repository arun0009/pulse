package io.github.arun0009.pulse.tracing.internal;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;

/**
 * Internal helpers for safely interacting with the Micrometer tracing API.
 *
 * <p>Centralises the null/no-op handling that every Pulse component needs so individual call
 * sites stay readable: {@code Tracer.currentSpan()} can return {@code null} (in some bridge
 * implementations) and {@code Span.NOOP} (when no recording span is active), and Pulse must
 * never call {@code tag()}/{@code event()}/{@code error()} in either case.
 *
 * <p>Pulse 2.0 chose Micrometer's {@link Tracer} façade over OpenTelemetry's {@code Span.current()}
 * static so applications using any micrometer-tracing bridge (OTel, Brave, custom) get the same
 * behaviour. The trade-off is that Micrometer's {@link Span} cannot attach attributes to a
 * specific event — those are temporarily lost until the Phase 4e Observation refactor.
 */
public final class PulseSpans {

    private PulseSpans() {}

    /**
     * Returns the current {@link Span} if one is recording, otherwise {@code null}. A {@code null}
     * tracer (defensive) and {@link Span#isNoop() no-op} spans both return {@code null} so the
     * caller can write a single {@code if (span != null)} guard.
     */
    public static @Nullable Span recordable(@Nullable Tracer tracer) {
        if (tracer == null) return null;
        Span span = tracer.currentSpan();
        if (span == null || span.isNoop()) return null;
        return span;
    }

    /**
     * True when {@code tracer} reports a current span with a non-empty trace id. Mirrors the
     * pre-2.0 {@code Span.current().getSpanContext().isValid()} check used by feature-toggle
     * code paths (TraceGuard, request-fanout, db, retry filter).
     */
    public static boolean hasValidContext(@Nullable Tracer tracer) {
        Span span = recordable(tracer);
        if (span == null) return false;
        TraceContext ctx = span.context();
        if (ctx == null) return false;
        String traceId = ctx.traceId();
        return traceId != null && !traceId.isEmpty();
    }
}
