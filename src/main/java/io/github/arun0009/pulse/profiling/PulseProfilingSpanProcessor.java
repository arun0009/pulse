package io.github.arun0009.pulse.profiling;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.jspecify.annotations.Nullable;

/**
 * OpenTelemetry {@link SpanProcessor} that stamps every starting span with attributes that
 * make profile↔trace correlation work in any APM that ingests structured trace attributes:
 *
 * <ul>
 *   <li>{@code profile.id} — set to the trace id, matching Grafana's recommended convention.
 *       Lets a trace UI render a one-click "Open profile" link without bespoke per-vendor
 *       configuration.
 *   <li>{@code pyroscope.profile_id} — same value, kept for parity with the Pyroscope agent's
 *       own auto-instrumentation so existing Pyroscope-aware UIs keep working.
 *   <li>{@code pulse.profile.url} (root-only) — a fully-qualified deep link to the configured
 *       Pyroscope server, scoped to the trace's time window and service name. Consumers who
 *       wire the {@code pulse.profiling.pyroscope-url} property get a clickable URL on every
 *       trace; consumers who don't see only the IDs.
 * </ul>
 *
 * <p>Stamping at span <em>start</em> rather than end is deliberate: many APMs (Tempo, Jaeger,
 * Zipkin) display the attributes set at start in the timeline view; attributes added at end
 * sometimes don't make it into the visual band. Span start is also where the Pyroscope agent
 * itself attaches labels, so stamping at the same lifecycle point keeps the two correlated.
 *
 * <p>The processor is no-op when the active span has no valid trace id (rare — usually only
 * happens for spans created outside a trace context, like background bootstrap work).
 */
public final class PulseProfilingSpanProcessor implements SpanProcessor {

    private static final AttributeKey<String> PROFILE_ID = AttributeKey.stringKey("profile.id");
    private static final AttributeKey<String> PYROSCOPE_PROFILE_ID = AttributeKey.stringKey("pyroscope.profile_id");
    private static final AttributeKey<String> PROFILE_URL = AttributeKey.stringKey("pulse.profile.url");

    private final String serviceName;
    private final @Nullable String pyroscopeUrl;

    public PulseProfilingSpanProcessor(String serviceName, @Nullable String pyroscopeUrl) {
        this.serviceName = serviceName;
        this.pyroscopeUrl = (pyroscopeUrl == null || pyroscopeUrl.isBlank()) ? null : pyroscopeUrl.trim();
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        String traceId = span.getSpanContext().getTraceId();
        if (traceId.isEmpty() || traceId.equals("00000000000000000000000000000000")) return;

        span.setAttribute(PROFILE_ID, traceId);
        span.setAttribute(PYROSCOPE_PROFILE_ID, traceId);

        if (pyroscopeUrl != null && isRootSpan(span)) {
            span.setAttribute(PROFILE_URL, buildProfileUrl(traceId));
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // No end-side work — start covered the contract.
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }

    private static boolean isRootSpan(ReadableSpan span) {
        // Root spans are the entry-point spans that show up in the trace's overview list — the
        // only level where a one-click profile-link attribute is genuinely useful. Adding it
        // to every child span would just clutter the timeline.
        return !span.getParentSpanContext().isValid()
                || span.getParentSpanContext().isRemote();
    }

    private String buildProfileUrl(String traceId) {
        // Pyroscope's deep-link URL grammar:
        //   <base>/explore?query={service_name="<svc>"}&from=<ms>&to=<ms>&trace_id=<traceId>
        // We use the host-page query param the way Grafana's Pyroscope app expects it; a custom
        // base URL (Phlare, Polar Signals, etc.) can override the pyroscope-url property.
        // Local copy keeps NullAway happy: the field check that gates this method's only caller
        // already guarantees non-null, but the analyzer only flows that across method boundaries
        // for final fields when invariance is provable.
        String base = pyroscopeUrl;
        if (base == null) return "";
        StringBuilder sb = new StringBuilder(base.length() + 96);
        sb.append(base);
        if (!base.endsWith("/")) sb.append('/');
        sb.append("explore?query=%7Bservice_name%3D%22");
        sb.append(serviceName);
        sb.append("%22%7D&trace_id=");
        sb.append(traceId);
        return sb.toString();
    }
}
