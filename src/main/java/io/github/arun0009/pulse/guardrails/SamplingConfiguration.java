package io.github.arun0009.pulse.guardrails;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enforces a {@code ParentBased(TraceIdRatioBased)} sampler so that:
 *
 * <ul>
 *   <li>If a parent trace exists (this is a downstream service), the parent decision is honored —
 *       no orphaned half-traces.
 *   <li>If a new trace is starting at this hop, sample at the configured ratio.
 * </ul>
 *
 * <p>This is the single most common configuration mistake in production OTel setups: a child
 * service overriding the parent decision and producing unjoinable spans. Pulse defaults to the
 * correct behavior.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Sampler.class)
public class SamplingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Sampler pulseSampler(PulseProperties properties) {
        return Sampler.parentBased(
                Sampler.traceIdRatioBased(properties.sampling().probability()));
    }
}
