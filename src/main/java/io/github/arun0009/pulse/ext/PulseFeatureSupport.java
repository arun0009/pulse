package io.github.arun0009.pulse.ext;

import io.github.arun0009.pulse.enforcement.PulseEnforcementMode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

/**
 * Shared helpers for custom {@link PulseFeature} implementations so application code can align
 * with Pulse's global enforcement semantics and reuse Micrometer tracing/metrics the same way
 * built-in subsystems do.
 *
 * <p>Inject this bean into your filters, interceptors, or services. It is always available when
 * Pulse auto-configuration runs.
 */
public final class PulseFeatureSupport {

    private final PulseEnforcementMode enforcement;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final ObjectProvider<Tracer> tracer;

    public PulseFeatureSupport(
            PulseEnforcementMode enforcement,
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<Tracer> tracer) {
        this.enforcement = enforcement;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
    }

    /** @see PulseEnforcementMode#enforcing() */
    public boolean enforcing() {
        return enforcement.enforcing();
    }

    /** @see PulseEnforcementMode#dryRun() */
    public boolean dryRun() {
        return enforcement.dryRun();
    }

    /** @see PulseEnforcementMode#get() */
    public PulseEnforcementMode.Mode enforcementMode() {
        return enforcement.get();
    }

    /**
     * Runs {@code onEnforce} when Pulse is in {@link PulseEnforcementMode.Mode#ENFORCING ENFORCING},
     * otherwise {@code onObserve} (typically the DRY_RUN path: metrics/logs only, no hard failure).
     */
    public void runEither(Runnable onEnforce, Runnable onObserve) {
        if (enforcement.enforcing()) {
            onEnforce.run();
        } else {
            onObserve.run();
        }
    }

    /** Micrometer registry when wired; empty when absent (e.g. tests with metrics stripped). */
    public Optional<MeterRegistry> meters() {
        return Optional.ofNullable(meterRegistry.getIfAvailable());
    }

    /**
     * Micrometer {@link Tracer} or {@link Tracer#NOOP} when tracing is off — never {@code null}.
     */
    public Tracer tracer() {
        return tracer.getIfAvailable(() -> Tracer.NOOP);
    }

    /** Live enforcement bean for advanced callers (e.g. flipping mode in tests). */
    public PulseEnforcementMode enforcement() {
        return enforcement;
    }
}
