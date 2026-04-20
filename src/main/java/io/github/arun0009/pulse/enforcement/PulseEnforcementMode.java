package io.github.arun0009.pulse.enforcement;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide observe-vs-enforce lever for every Pulse guardrail.
 *
 * <h2>Two knobs, not three</h2>
 *
 * Pulse intentionally separates <em>"is this feature on at all?"</em> from
 * <em>"when on, does it observe or enforce?"</em>:
 *
 * <ul>
 *   <li>The first question is answered per-feature by {@code pulse.<feature>.enabled} (default
 *       {@code true}). Set it to {@code false} when you don't want a feature to even register
 *       its beans / filters.
 *   <li>The second question is answered globally by this mode:
 *       <ul>
 *         <li>{@link Mode#ENFORCING} — default. Each enforcing feature behaves as configured
 *             (the trace-context guard returns 400 when {@code fail-on-missing=true}, the
 *             cardinality firewall rewrites runaway tag values to {@code OVERFLOW}, etc.).
 *         <li>{@link Mode#DRY_RUN} — observation-only. Features still emit metrics, span
 *             events, and log lines so dashboards keep showing what would happen, but Pulse
 *             does not enforce. Useful for safe-rolling Pulse into an existing fleet — flip
 *             back to {@code ENFORCING} once dashboards show the impact you expect.
 *       </ul>
 * </ul>
 *
 * <h2>Why no OFF mode</h2>
 *
 * Earlier 1.1 milestones included a third {@code OFF} state acting as a global killswitch. It
 * was redundant: every Pulse feature is already guarded by its own {@code pulse.<feature>.enabled}
 * property, and using a global runtime knob to "turn everything off" hides which feature was
 * actually problematic. The single-feature toggle remains the right granularity for incident
 * response, and removing the third state collapses the conditional logic in every filter.
 *
 * <h2>Initial mode and runtime mutation</h2>
 *
 * The mode is initialised from {@code pulse.enforcement.mode} at startup and can be changed at
 * runtime via {@code POST /actuator/pulse/enforcement} with body {@code {"value":"DRY_RUN"}}.
 * The change takes effect on the very next request — there is no cached decision a feature has
 * to invalidate.
 *
 * <p>This bean is always created (regardless of property value) so the actuator endpoint can
 * flip between modes during incident response without a redeploy.
 */
public final class PulseEnforcementMode {

    /** Two-state enforcement gate. See {@link PulseEnforcementMode}. */
    public enum Mode {
        ENFORCING,
        DRY_RUN
    }

    private final AtomicReference<Mode> current;

    public PulseEnforcementMode(Mode initial) {
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
}
