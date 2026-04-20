package io.github.arun0009.pulse.guardrails.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.guardrails.PreferErrorSampler;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Adds Pulse's error-bias pass to whatever {@link Sampler} Spring Boot wires.
 *
 * <p>The head sampling rate is intentionally owned by Boot's
 * {@code management.tracing.sampling.probability}: shadowing it with a {@code pulse.sampling.*}
 * property leads to confusing precedence questions and silent drift between the value in the
 * banner and the value the SDK actually uses. Pulse therefore does <em>not</em> publish a
 * {@code Sampler} bean of its own — instead, when
 * {@code pulse.sampling.prefer-sampling-on-error=true} (default), a {@link BeanPostProcessor}
 * wraps the existing {@code Sampler} bean (Boot's, the user's custom one, or the OTel SDK
 * default, in that order of precedence) with {@link PreferErrorSampler}.
 *
 * <p>The wrapper is a pass-through for every span whose start attributes do not show error
 * indicators, so the configured probability still drives ordinary traffic. Spans that already
 * look like errors at start are upgraded to {@code RECORD_AND_SAMPLE}, recovering the high-value
 * tail that a low-rate head sampler would otherwise drop.
 *
 * <p>The BPP is idempotent (a {@code PreferErrorSampler} is never wrapped a second time) and
 * registered as a {@code static} {@code @Bean} so Spring can instantiate it before regular
 * bean creation begins.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnClass(Sampler.class)
public class SamplingConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "pulse.sampling",
            name = "prefer-sampling-on-error",
            havingValue = "true",
            matchIfMissing = true)
    public static BeanPostProcessor pulsePreferErrorSamplerWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof PreferErrorSampler) return bean;
                if (bean instanceof Sampler base) {
                    return new PreferErrorSampler(base);
                }
                return bean;
            }
        };
    }
}
