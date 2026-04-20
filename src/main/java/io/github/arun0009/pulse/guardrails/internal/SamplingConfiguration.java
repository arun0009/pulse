package io.github.arun0009.pulse.guardrails.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.guardrails.PreferErrorSampler;
import io.github.arun0009.pulse.guardrails.SamplingProperties;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Enforces a {@code ParentBased(TraceIdRatioBased)} sampler so that:
 *
 * <ul>
 *   <li>If a parent trace exists (this is a downstream service), the parent decision is honored —
 *       no orphaned half-traces.
 *   <li>If a new trace is starting at this hop, sample at the configured ratio.
 *   <li>If {@code pulse.sampling.prefer-sampling-on-error=true} (default), errors that are visible
 *       at span start are upgraded to {@code RECORD_AND_SAMPLE} regardless of the ratio. See
 *       {@link PreferErrorSampler} for the honest limits of this heuristic.
 * </ul>
 *
 * <p>This is the single most common configuration mistake in production OTel setups: a child
 * service overriding the parent decision and producing unjoinable spans. Pulse defaults to the
 * correct behavior.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnClass(Sampler.class)
public class SamplingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Sampler pulseSampler(SamplingProperties sampling) {
        Sampler base = Sampler.parentBased(Sampler.traceIdRatioBased(sampling.probability()));
        if (sampling.preferSamplingOnError()) {
            return new PreferErrorSampler(base);
        }
        return base;
    }
}
