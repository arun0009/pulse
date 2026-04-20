package io.github.arun0009.pulse.enforcement;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import io.github.arun0009.pulse.core.PulseRequestMatcher;
import io.github.arun0009.pulse.core.TraceGuardFilter;
import io.github.arun0009.pulse.core.TraceGuardProperties;
import io.github.arun0009.pulse.guardrails.CardinalityFirewall;
import io.github.arun0009.pulse.guardrails.CardinalityProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dry-run mode is the operational lever for safe-rolling Pulse: when an enforcing feature is
 * suspected of mis-behaving in production, an SRE flips the actuator endpoint and the very next
 * request must observe the change without a redeploy. These tests pin the contract for each
 * enforcing feature:
 *
 * <ul>
 *   <li>{@code DRY_RUN} — diagnostics still emit so dashboards keep working, but enforcement is
 *       disabled. This is the safe-roll mode for new fleets.
 *   <li>{@code ENFORCING} — baseline production behaviour: the firewall rewrites overflow tags,
 *       the trace-context guard returns 4xx on missing context (when configured to do so).
 *   <li>Switching modes at runtime takes effect on the next request — there is no cached decision.
 * </ul>
 *
 * <p>Pulse intentionally exposes only these two modes. To take a feature out of the picture
 * entirely, set the per-feature {@code pulse.<feature>.enabled=false} property — that is the
 * right granularity for incident response.
 */
class PulseEnforcementModeWiringTest {

    @Test
    void trace_guard_dry_run_observes_but_never_rejects() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PulseEnforcementMode enforcement = new PulseEnforcementMode(PulseEnforcementMode.Mode.DRY_RUN);
        TraceGuardFilter guard = new TraceGuardFilter(
                registry,
                new TraceGuardProperties(true, true, List.of(), PulseRequestMatcherProperties.empty()),
                PulseRequestMatcher.ALWAYS,
                enforcement);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        AtomicBoolean reachedDownstream = new AtomicBoolean(false);
        FilterChain chain = (req, resp) -> reachedDownstream.set(true);

        guard.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(reachedDownstream)
                .as("dry-run must let the request continue even when fail-on-missing=true")
                .isTrue();
        assertThat(registry.find("pulse.trace.missing").counter())
                .as("dry-run still records the missing-context signal so dashboards keep working")
                .isNotNull();
    }

    @Test
    void trace_guard_enforcing_with_fail_on_missing_throws_as_baseline() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PulseEnforcementMode enforcement = new PulseEnforcementMode(PulseEnforcementMode.Mode.ENFORCING);
        TraceGuardFilter guard = new TraceGuardFilter(
                registry,
                new TraceGuardProperties(true, true, List.of(), PulseRequestMatcherProperties.empty()),
                PulseRequestMatcher.ALWAYS,
                enforcement);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");

        assertThatThrownBy(() -> guard.doFilter(request, new MockHttpServletResponse(), (req, resp) -> {}))
                .isInstanceOf(ServletException.class)
                .hasMessageContaining("missing trace-context");
    }

    @Test
    void trace_guard_runtime_mode_change_takes_effect_on_next_request() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PulseEnforcementMode enforcement = new PulseEnforcementMode(PulseEnforcementMode.Mode.ENFORCING);
        TraceGuardFilter guard = new TraceGuardFilter(
                registry,
                new TraceGuardProperties(true, true, List.of(), PulseRequestMatcherProperties.empty()),
                PulseRequestMatcher.ALWAYS,
                enforcement);

        assertThatThrownBy(() -> guard.doFilter(
                        new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), (r, s) -> {}))
                .isInstanceOf(ServletException.class);

        enforcement.set(PulseEnforcementMode.Mode.DRY_RUN);

        // Same filter, same config — but now the request must succeed because the gate flipped.
        guard.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), (r, s) -> {});
    }

    @Test
    void cardinality_firewall_dry_run_observes_overflow_but_does_not_clamp() {
        CardinalityProperties config = new CardinalityProperties(true, 5, "OVERFLOW", List.of(), List.of());
        PulseEnforcementMode enforcement = new PulseEnforcementMode(PulseEnforcementMode.Mode.DRY_RUN);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CardinalityFirewall firewall = new CardinalityFirewall(config, enforcement, () -> registry);
        registry.config().meterFilter(firewall);

        for (int i = 0; i < 50; i++) {
            registry.counter("orders.placed", "userId", "user-" + i).increment();
        }

        long distinct = registry.find("orders.placed").counters().stream()
                .map(c -> c.getId().getTag("userId"))
                .filter(v -> !"OVERFLOW".equals(v))
                .distinct()
                .count();
        assertThat(distinct).as("dry-run must not rewrite tag values").isEqualTo(50);
        assertThat(registry.find("pulse.cardinality.overflow").counter())
                .as("dry-run still increments the overflow diagnostic so SREs see the impact")
                .isNotNull();
    }

    @Test
    void cardinality_firewall_enforcing_clamps_runaway_tags() {
        CardinalityProperties config = new CardinalityProperties(true, 5, "OVERFLOW", List.of(), List.of());
        PulseEnforcementMode enforcement = new PulseEnforcementMode(PulseEnforcementMode.Mode.ENFORCING);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CardinalityFirewall firewall = new CardinalityFirewall(config, enforcement, () -> registry);
        registry.config().meterFilter(firewall);

        for (int i = 0; i < 50; i++) {
            registry.counter("orders.placed", "userId", "user-" + i).increment();
        }

        long distinct = registry.find("orders.placed").counters().stream()
                .map(c -> c.getId().getTag("userId"))
                .filter(v -> !"OVERFLOW".equals(v))
                .distinct()
                .count();
        assertThat(distinct)
                .as("enforcing mode must rewrite values past the budget to the overflow sentinel")
                .isEqualTo(5);
        assertThat(registry.find("pulse.cardinality.overflow").counter())
                .as("the overflow diagnostic counter is still maintained")
                .isNotNull();
    }
}
