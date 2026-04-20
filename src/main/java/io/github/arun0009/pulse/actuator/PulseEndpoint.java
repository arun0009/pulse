package io.github.arun0009.pulse.actuator;

import io.github.arun0009.pulse.enforcement.PulseEnforcementMode;
import io.github.arun0009.pulse.fleet.ConfigHasher;
import io.github.arun0009.pulse.slo.SloRuleGenerator;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.LinkedHashMap;
import java.util.Locale;
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
 *   <li>{@code GET /actuator/pulse/config-hash} — deterministic hash of the effective config plus
 *       a flattened view, for fleet drift detection.
 *   <li>{@code GET /actuator/pulse/enforcement} — current enforcement mode (ENFORCING / DRY_RUN).
 *   <li>{@code POST /actuator/pulse/enforcement} {@code {"value":"DRY_RUN"}} — flip the
 *       enforcement mode for this process. The change takes effect on the next request, no
 *       restart required.
 * </ul>
 *
 * <p>The legacy {@code /actuator/pulse/mode} segment from the 1.1 milestone is still accepted as
 * a deprecated alias and will be removed in a future minor release. Migrate scripts to use the
 * {@code enforcement} segment.
 *
 * <p>Exposing the {@code POST} requires {@code management.endpoint.pulse.access=unrestricted} (or
 * {@code read-write}, depending on your Spring Boot version's actuator-access wording) — Spring
 * Boot's default is read-only.
 */
@Endpoint(id = "pulse")
public class PulseEndpoint {

    private final PulseDiagnostics diagnostics;
    private final @Nullable SloRuleGenerator sloRules;

    public PulseEndpoint(PulseDiagnostics diagnostics, @Nullable SloRuleGenerator sloRules) {
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
            return sloRules == null ? "# SLO subsystem disabled (pulse.slo.enabled=false)\n" : sloRules.render();
        }
        if ("effective-config".equals(segment)) {
            return diagnostics.effectiveConfig();
        }
        if ("runtime".equals(segment)) {
            return diagnostics.runtime();
        }
        if ("config-hash".equals(segment)) {
            Map<String, Object> body = new LinkedHashMap<>();
            Object cfg = diagnostics.effectiveConfig();
            body.put("hash", ConfigHasher.hash(cfg));
            body.put("entries", ConfigHasher.flatten(cfg));
            return body;
        }
        if ("enforcement".equals(segment) || "mode".equals(segment)) {
            PulseEnforcementMode enforcement = diagnostics.enforcementMode();
            return Map.of(
                    "mode",
                    enforcement == null ? "ENFORCING" : enforcement.get().name());
        }
        return null;
    }

    /**
     * Enforcement-mode write. Accepts {@code POST /actuator/pulse/enforcement} (canonical) or
     * the deprecated {@code POST /actuator/pulse/mode} alias with body
     * {@code {"value": "DRY_RUN"}}. Returns the previous and current mode so an operator can
     * confirm the flip in their shell history.
     *
     * @param segment one of {@code "enforcement"} (canonical) or {@code "mode"} (deprecated)
     * @param value one of {@code ENFORCING}, {@code DRY_RUN} (case-insensitive)
     * @return the previous and current mode
     */
    @WriteOperation
    public Map<String, Object> write(@Selector String segment, String value) {
        if (!"enforcement".equals(segment) && !"mode".equals(segment)) {
            return Map.of(
                    "error",
                    "Unknown segment: " + segment + ". Supported writable segments: [enforcement] (alias: mode).");
        }
        PulseEnforcementMode enforcement = diagnostics.enforcementMode();
        if (enforcement == null) {
            return Map.of(
                    "error",
                    "PulseEnforcementMode bean was not wired into PulseDiagnostics. The"
                            + " enforcement lever is unavailable on this build.");
        }
        PulseEnforcementMode.Mode previous = enforcement.get();
        PulseEnforcementMode.Mode next;
        try {
            next = PulseEnforcementMode.Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Map.of("error", "Unknown mode: " + value + ". Allowed: [ENFORCING, DRY_RUN].");
        }
        enforcement.set(next);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("previous", previous.name());
        body.put("current", next.name());
        body.put("note", "Change is in-memory and per-process. Persist via pulse.enforcement.mode in application.yml.");
        if ("mode".equals(segment)) {
            body.put(
                    "deprecation",
                    "/actuator/pulse/mode is a deprecated alias for /actuator/pulse/enforcement"
                            + " and will be removed in a future minor release.");
        }
        return body;
    }
}
