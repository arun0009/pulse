package io.github.arun0009.pulse.ext;

/**
 * Register application-specific observability or guardrail behaviour as a first-class Pulse
 * extension.
 *
 * <p>Implement this interface on a Spring {@code @Bean}. Pulse discovers every {@code PulseFeature}
 * bean and lists it under the {@code userFeatures} key in {@code GET /actuator/pulse} (and the
 * pulse UI) so operators see what your service registered alongside built-in subsystems.
 *
 * <p>This interface is intentionally <em>metadata-only</em>: you still wire real behaviour with
 * normal Spring primitives (filters, interceptors, {@code ObservationHandler}, Micrometer meters,
 * etc.). Pair those beans with {@link PulseFeatureSupport} so custom code respects the same
 * {@link io.github.arun0009.pulse.enforcement.PulseEnforcementMode} semantics as built-in Pulse
 * features.
 *
 * @see PulseFeatureSupport
 */
public interface PulseFeature {

    /**
     * Stable identifier for this extension (logs, dashboards, actuator JSON). Use a dotted name
     * you own, e.g. {@code acme.ratelimit}.
     */
    String id();

    /**
     * Short title for humans; defaults to {@link #id()}.
     */
    default String displayName() {
        return id();
    }

    /**
     * One-line description of what this extension does when enabled.
     */
    default String description() {
        return "";
    }

    /**
     * Whether this extension considers itself active. Pulse still lists the feature when {@code
     * false} so operators can see a declared-but-disabled integration; override to bind from your
     * own {@code @ConfigurationProperties} if needed.
     */
    default boolean enabled() {
        return true;
    }
}
