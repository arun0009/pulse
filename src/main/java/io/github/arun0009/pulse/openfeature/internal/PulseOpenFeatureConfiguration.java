package io.github.arun0009.pulse.openfeature.internal;

import dev.openfeature.sdk.OpenFeatureAPI;
import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.openfeature.PulseOpenFeatureMdcHook;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-wires Pulse's OpenFeature integration when the {@code dev.openfeature:sdk} is on the
 * classpath.
 *
 * <p>The bean-factory methods below are intentionally side-effect-free — they only <em>build</em>
 * the hook beans. The actual mutation of the JVM-global {@link OpenFeatureAPI#getInstance()}
 * singleton happens inside {@link PulseOpenFeatureHookRegistrar}, in a single well-defined
 * {@link org.springframework.beans.factory.SmartInitializingSingleton} step and guarded by a
 * JVM-wide "already-registered" set so repeated Spring context refreshes (test harnesses,
 * actuator reloads) cannot leak duplicate hook registrations.
 *
 * <p>Two hooks end up registered:
 *
 * <ol>
 *   <li>{@link PulseOpenFeatureMdcHook} — always registered, threads flag values onto MDC and
 *       stamps a {@code feature_flag} span event matching OTel semantic conventions.
 *   <li>The upstream {@code dev.openfeature.contrib.hooks.otel.OpenTelemetryHook} — registered
 *       reflectively when present so consumers do not have to wire it themselves. The hook lives
 *       in a separate artifact ({@code dev.openfeature.contrib.hooks:otel}) which Pulse does not
 *       declare; the registration is a no-op when the artifact is absent.
 * </ol>
 *
 * <p>Setting {@code pulse.open-feature.enabled=false} suppresses both registrations.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnClass(OpenFeatureAPI.class)
@ConditionalOnProperty(prefix = "pulse.open-feature", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PulseOpenFeatureConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PulseOpenFeatureMdcHook pulseOpenFeatureMdcHook(ObjectProvider<Tracer> tracer) {
        return new PulseOpenFeatureMdcHook(tracer.getIfAvailable(() -> Tracer.NOOP));
    }

    @Bean
    @ConditionalOnMissingBean
    public PulseOpenFeatureHookRegistrar pulseOpenFeatureHookRegistrar(PulseOpenFeatureMdcHook mdcHook) {
        return new PulseOpenFeatureHookRegistrar(mdcHook);
    }
}
