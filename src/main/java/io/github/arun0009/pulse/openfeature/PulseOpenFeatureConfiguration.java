package io.github.arun0009.pulse.openfeature;

import dev.openfeature.sdk.OpenFeatureAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-wires Pulse's OpenFeature integration when the {@code dev.openfeature:sdk} is on the
 * classpath. Registers two hooks on the global {@link OpenFeatureAPI}:
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
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OpenFeatureAPI.class)
@ConditionalOnProperty(prefix = "pulse.open-feature", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PulseOpenFeatureConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PulseOpenFeatureConfiguration.class);
    private static final String OTEL_HOOK_CLASS = "dev.openfeature.contrib.hooks.otel.OpenTelemetryHook";

    @Bean
    @ConditionalOnMissingBean
    public PulseOpenFeatureMdcHook pulseOpenFeatureMdcHook() {
        PulseOpenFeatureMdcHook hook = new PulseOpenFeatureMdcHook();
        try {
            OpenFeatureAPI.getInstance().addHooks(hook);
        } catch (RuntimeException e) {
            log.debug("Pulse OpenFeature MDC hook registration failed", e);
        }
        return hook;
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseOpenFeatureOtelHookRegistration")
    public PulseOpenFeatureOtelHookRegistration pulseOpenFeatureOtelHookRegistration() {
        PulseOpenFeatureOtelHookRegistration registration = new PulseOpenFeatureOtelHookRegistration();
        registration.tryRegister();
        return registration;
    }

    /**
     * Marker bean whose construction is the side effect of attempting to register the upstream
     * OpenTelemetry hook. The hook lives in an optional artifact; reflection avoids a hard
     * compile-time dependency.
     */
    public static final class PulseOpenFeatureOtelHookRegistration {
        boolean registered = false;

        public boolean registered() {
            return registered;
        }

        void tryRegister() {
            try {
                Class<?> hookClass = Class.forName(OTEL_HOOK_CLASS);
                Object hook = hookClass.getDeclaredConstructor().newInstance();
                if (hook instanceof dev.openfeature.sdk.Hook<?> otelHook) {
                    OpenFeatureAPI.getInstance().addHooks(otelHook);
                    registered = true;
                    log.info("Pulse: registered OpenFeature OpenTelemetry hook for flag-evaluation tracing");
                }
            } catch (ClassNotFoundException ignored) {
                // OTel hook artifact is opt-in; absence is the common case and not an error.
            } catch (ReflectiveOperationException | RuntimeException e) {
                log.debug("Pulse: failed to register OpenFeature OpenTelemetry hook", e);
            }
        }
    }
}
