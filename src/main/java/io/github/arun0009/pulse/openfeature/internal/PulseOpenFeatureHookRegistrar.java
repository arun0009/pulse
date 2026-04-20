package io.github.arun0009.pulse.openfeature.internal;

import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.github.arun0009.pulse.openfeature.PulseOpenFeatureMdcHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idempotently registers Pulse's OpenFeature hooks on the JVM-global
 * {@link OpenFeatureAPI#getInstance()} singleton, exactly once per registrar bean AND only
 * once per hook class across the life of the JVM.
 *
 * <p>The {@code OpenFeatureAPI} instance is a process-wide global, not owned by Spring. If a
 * Spring context is created and refreshed multiple times (the common case for test suites that
 * fire up {@code ApplicationContextRunner}) a naive
 * {@code OpenFeatureAPI.getInstance().addHooks(...)} call from inside a {@code @Bean} method
 * would leak duplicate hook registrations into every subsequent test run on the same JVM. The
 * JVM-level deduplication below makes {@link PulseOpenFeatureConfiguration} safe to instantiate
 * repeatedly.
 *
 * <p>Registration happens in {@link #afterSingletonsInstantiated()} — i.e. the single
 * well-defined lifecycle moment after every bean has been created — so the bean-factory methods
 * that build the hooks themselves stay side-effect-free.
 */
public final class PulseOpenFeatureHookRegistrar implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PulseOpenFeatureHookRegistrar.class);
    private static final String OTEL_HOOK_CLASS = "dev.openfeature.contrib.hooks.otel.OpenTelemetryHook";

    /** Hook class names already registered on this JVM's {@code OpenFeatureAPI} singleton. */
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

    private final PulseOpenFeatureMdcHook mdcHook;
    private volatile boolean otelHookRegistered;

    public PulseOpenFeatureHookRegistrar(PulseOpenFeatureMdcHook mdcHook) {
        this.mdcHook = mdcHook;
    }

    @Override
    public void afterSingletonsInstantiated() {
        registerIdempotently(mdcHook);
        this.otelHookRegistered = registerOtelHookReflectively();
    }

    /**
     * Returns {@code true} iff the optional upstream OTel hook was registered during init.
     *
     * @return whether the OTel hook is active on the global OpenFeature API.
     */
    public boolean otelHookRegistered() {
        return otelHookRegistered;
    }

    private static void registerIdempotently(Hook<?> hook) {
        String key = hook.getClass().getName();
        if (!REGISTERED.add(key)) {
            return;
        }
        try {
            OpenFeatureAPI.getInstance().addHooks(hook);
        } catch (RuntimeException e) {
            REGISTERED.remove(key);
            log.debug("Pulse: failed to register OpenFeature hook {}", key, e);
        }
    }

    private static boolean registerOtelHookReflectively() {
        if (REGISTERED.contains(OTEL_HOOK_CLASS)) {
            return true;
        }
        try {
            Class<?> hookClass = Class.forName(OTEL_HOOK_CLASS);
            Object hook = hookClass.getDeclaredConstructor().newInstance();
            if (hook instanceof Hook<?> otelHook) {
                registerIdempotently(otelHook);
                log.info("Pulse: registered OpenFeature OpenTelemetry hook for flag-evaluation tracing");
                return REGISTERED.contains(OTEL_HOOK_CLASS);
            }
            return false;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.debug("Pulse: failed to register OpenFeature OpenTelemetry hook", e);
            return false;
        }
    }
}
