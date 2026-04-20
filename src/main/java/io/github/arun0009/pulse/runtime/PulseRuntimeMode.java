package io.github.arun0009.pulse.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide killswitch and dry-run lever for every Pulse subsystem.
 *
 * <p>Three modes:
 *
 * <ul>
 *   <li>{@link Mode#ENFORCING} — default. Every Pulse feature behaves as configured.
 *   <li>{@link Mode#DRY_RUN} — observation-only. Features still emit metrics, span events, and log
 *       lines so dashboards keep showing what would happen, but Pulse does not enforce. The
 *       trace-context guard still increments {@code pulse.trace.missing} but never returns HTTP 400.
 *       The cardinality firewall still increments {@code pulse.cardinality.overflow} and warns once,
 *       but lets the runaway tag value through. Useful for safe-rolling Pulse into an existing fleet
 *       — flip ENFORCING once dashboards show the impact you expect.
 *   <li>{@link Mode#OFF} — full killswitch. Every Pulse feature short-circuits as if its
 *       {@code .enabled=false} property had been set. Reserved for incident response: when a Pulse
 *       feature is wrongly accused, ops can flip OFF in seconds without a redeploy.
 * </ul>
 *
 * <p>The mode is initialised from {@code pulse.runtime.mode} at startup and can be changed at
 * runtime via {@code POST /actuator/pulse/mode} with body {@code {"value": "DRY_RUN"}}. The
 * change takes effect on the very next request — there is no cached decision a feature has to
 * invalidate.
 *
 * <p>This bean is always created (regardless of {@code pulse.runtime.mode}) so that the actuator
 * endpoint can flip OFF -> ENFORCING just as easily as the reverse.
 */
public final class PulseRuntimeMode {

    /** Three-state runtime gate. See {@link PulseRuntimeMode}. */
    public enum Mode {
        ENFORCING,
        DRY_RUN,
        OFF
    }

    private final AtomicReference<Mode> current;

    public PulseRuntimeMode(Mode initial) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial mode"));
    }

    public Mode get() {
        return current.get();
    }

    public void set(Mode mode) {
        current.set(Objects.requireNonNull(mode, "mode"));
    }

    /** {@code true} when Pulse should fully enforce — default mode. */
    public boolean enforcing() {
        return current.get() == Mode.ENFORCING;
    }

    /** {@code true} when Pulse should observe but never enforce. */
    public boolean dryRun() {
        return current.get() == Mode.DRY_RUN;
    }

    /** {@code true} when Pulse is fully off (killswitch active). */
    public boolean off() {
        return current.get() == Mode.OFF;
    }

    /**
     * Convenience: opposite of {@link #off()}. Most feature filters use this as the very first
     * short-circuit — "should I do anything at all on this request?".
     */
    public boolean active() {
        return current.get() != Mode.OFF;
    }
}
