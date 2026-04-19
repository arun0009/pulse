package io.github.arun0009.pulse.actuator;

import io.github.arun0009.pulse.slo.SloRuleGenerator;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import java.util.Map;

/**
 * {@code GET /actuator/pulse} — live snapshot of every Pulse subsystem so operators can verify what
 * is on, what is off, and what configuration won at runtime. Self-documenting by design.
 *
 * <p>Sub-paths:
 *
 * <ul>
 *   <li>{@code GET /actuator/pulse/slo} — generated PrometheusRule YAML for every declared SLO.
 *       Pipe it straight into {@code kubectl apply -f -}.
 *   <li>{@code GET /actuator/pulse/effective-config} — fully resolved {@code pulse.*} config.
 *   <li>{@code GET /actuator/pulse/runtime} — live counters and top offenders from runtime state.
 * </ul>
 */
@Endpoint(id = "pulse")
public class PulseEndpoint {

    private final PulseDiagnostics diagnostics;
    private final SloRuleGenerator sloRules;

    public PulseEndpoint(PulseDiagnostics diagnostics, SloRuleGenerator sloRules) {
        this.diagnostics = diagnostics;
        this.sloRules = sloRules;
    }

    @ReadOperation
    public Map<String, Object> read() {
        return diagnostics.snapshot();
    }

    @ReadOperation
    public @Nullable Object read(@Selector String segment) {
        if ("slo".equals(segment)) {
            return sloRules.render();
        }
        if ("effective-config".equals(segment)) {
            return diagnostics.effectiveConfig();
        }
        if ("runtime".equals(segment)) {
            return diagnostics.runtime();
        }
        return null;
    }
}
