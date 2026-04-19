package io.github.arun0009.pulse.openfeature;

import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
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

    @Override
    public void after(HookContext<Object> ctx, FlagEvaluationDetails<Object> details, Map<String, Object> hints) {
        String key = "feature_flag." + ctx.getFlagKey();
        Object value = details.getValue();
        MDC.put(key, value == null ? "null" : value.toString());

        Span span = Span.current();
        if (span.getSpanContext().isValid()) {
            AttributesBuilder attrs = Attributes.builder();
            attrs.put("feature_flag.key", ctx.getFlagKey());
            attrs.put("feature_flag.provider_name", ctx.getProviderMetadata().getName());
            String variant = details.getVariant();
            if (variant != null) attrs.put("feature_flag.variant", variant);
            span.addEvent("feature_flag", attrs.build());
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
