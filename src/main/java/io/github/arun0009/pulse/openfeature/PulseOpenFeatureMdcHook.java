package io.github.arun0009.pulse.openfeature;

import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import io.github.arun0009.pulse.tracing.internal.PulseSpans;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;

import java.util.Map;

/**
 * OpenFeature {@link Hook} that mirrors flag-evaluation outcomes onto MDC and onto the active
 * span so every downstream log line and trace explains why this request branched the way it did.
 *
 * <p>For each evaluation Pulse stamps:
 * <ul>
 *   <li>{@code feature_flag.<flag>} on MDC with the resolved value.
 *   <li>A {@code feature_flag} span event with {@code feature_flag.key},
 *       {@code feature_flag.provider_name}, and {@code feature_flag.variant} attributes
 *       — matching OpenTelemetry's feature-flag semantic conventions. When the upstream
 *       OpenFeature {@code OpenTelemetryHook} is also present, the operator gets both: they
 *       describe the same event, but the Pulse hook additionally threads the value into MDC
 *       which the OTel hook does not.
 * </ul>
 *
 * <p>MDC keys are scoped to the request and removed in
 * {@link #finallyAfter(HookContext, FlagEvaluationDetails, Map)} to prevent leakage across
 * pooled threads.
 */
public final class PulseOpenFeatureMdcHook implements Hook<Object> {

    private final Tracer tracer;

    public PulseOpenFeatureMdcHook() {
        this(Tracer.NOOP);
    }

    public PulseOpenFeatureMdcHook(Tracer tracer) {
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
    }

    @Override
    public void after(HookContext<Object> ctx, FlagEvaluationDetails<Object> details, Map<String, Object> hints) {
        String key = "feature_flag." + ctx.getFlagKey();
        Object value = details.getValue();
        MDC.put(key, value == null ? "null" : value.toString());

        Span span = PulseSpans.recordable(tracer);
        if (span != null) {
            // TODO(phase 4e): per-event attributes — Micrometer's Span has no addEvent(name, attrs)
            // overload, so the OTel feature-flag semconv attributes land as span tags rather than
            // event attributes. Restored when the Observation refactor lands.
            span.event("feature_flag");
            span.tag("feature_flag.key", ctx.getFlagKey());
            span.tag("feature_flag.provider_name", ctx.getProviderMetadata().getName());
            String variant = details.getVariant();
            if (variant != null) span.tag("feature_flag.variant", variant);
        }
    }

    @Override
    public void finallyAfter(
            HookContext<Object> ctx, FlagEvaluationDetails<Object> details, Map<String, Object> hints) {
        MDC.remove("feature_flag." + ctx.getFlagKey());
    }

    @Override
    public boolean supportsFlagValueType(FlagValueType flagValueType) {
        return true;
    }
}
