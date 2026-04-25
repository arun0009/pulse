package io.github.arun0009.pulse.ext.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.enforcement.PulseEnforcementMode;
import io.github.arun0009.pulse.ext.PulseFeatureSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Wires {@link PulseFeatureSupport} for application {@link io.github.arun0009.pulse.ext.PulseFeature}
 * implementations.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnBean(PulseEnforcementMode.class)
public class PulseFeatureConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PulseFeatureSupport pulseFeatureSupport(
            PulseEnforcementMode enforcement,
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<Tracer> tracer) {
        return new PulseFeatureSupport(enforcement, meterRegistry, tracer);
    }
}
