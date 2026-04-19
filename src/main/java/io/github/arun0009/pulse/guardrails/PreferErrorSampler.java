package io.github.arun0009.pulse.guardrails;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

import java.util.List;

/**
 * Composes an "error-bias" pass on top of the configured base {@link Sampler}.
 *
 * <p>OpenTelemetry sampling decisions are made <em>at span start</em>, before the span has had a
 * chance to fail — so a probability sampler set to 5% will drop 95% of error spans too. Most
 * teams react by either (a) cranking the rate up at huge cost, or (b) accepting the loss.
 *
 * <p>{@code PreferErrorSampler} provides a third option: at span start, defer to the base sampler.
 * If a strong hint of error is already present on the start attributes (e.g. a non-success
 * {@code http.response.status_code}, an {@code exception.type} attribute, or a span kind that's
 * known to be error-only), upgrade the decision to {@code RECORD_AND_SAMPLE}.
 *
 * <p><strong>Honest limits</strong>: this is an in-process best-effort heuristic, not true tail
 * sampling. It cannot upgrade a span whose error only manifests after start, and it cannot
 * coordinate across services. For production-grade tail sampling on errors, use the OpenTelemetry
 * Collector's {@code tail_sampling} processor with an {@code error} policy. Pulse intentionally
 * does not attempt to reimplement that here.
 */
public final class PreferErrorSampler implements Sampler {

    private static final AttributeKey<Long> HTTP_STATUS_CODE = AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<Long> HTTP_STATUS_CODE_LEGACY = AttributeKey.longKey("http.status_code");
    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> RPC_GRPC_STATUS = AttributeKey.stringKey("rpc.grpc.status_code");
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

    private final Sampler delegate;

    public PreferErrorSampler(Sampler delegate) {
        this.delegate = delegate;
    }

    @Override
    public SamplingResult shouldSample(
            Context parentContext,
            String traceId,
            String name,
            SpanKind spanKind,
            Attributes attributes,
            List<LinkData> parentLinks) {

        SamplingResult base = delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
        if (base.getDecision() == io.opentelemetry.sdk.trace.samplers.SamplingDecision.RECORD_AND_SAMPLE) {
            return base;
        }
        if (looksLikeError(attributes)) {
            return SamplingResult.recordAndSample();
        }
        return base;
    }

    @Override
    public String getDescription() {
        return "PreferErrorSampler{" + delegate.getDescription() + "}";
    }

    private static boolean looksLikeError(Attributes attributes) {
        Long status = attributes.get(HTTP_STATUS_CODE);
        if (status == null) status = attributes.get(HTTP_STATUS_CODE_LEGACY);
        if (status != null && status >= 500) return true;

        if (attributes.get(EXCEPTION_TYPE) != null) return true;
        if (attributes.get(ERROR_TYPE) != null) return true;

        String grpcStatus = attributes.get(RPC_GRPC_STATUS);
        if (grpcStatus != null && !"0".equals(grpcStatus) && !"OK".equalsIgnoreCase(grpcStatus)) return true;

        return false;
    }
}
