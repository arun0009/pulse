package io.github.arun0009.pulse.runtime;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import io.github.arun0009.pulse.core.PulseRequestMatcher;
import io.github.arun0009.pulse.core.TraceGuardFilter;
import io.github.arun0009.pulse.guardrails.CardinalityFirewall;
import io.github.arun0009.pulse.guardrails.TimeoutBudget;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The killswitch and dry-run mode are operational levers: when a Pulse feature is suspected of
 * causing trouble in production, an SRE flips the actuator and the very next request must observe
 * the change without a redeploy. These tests assert the contract for each enforcing feature:
 *
 * <ul>
 *   <li>{@code OFF} — short-circuits before any Pulse logic (no rejection, no rewrite, no baggage).
 *   <li>{@code DRY_RUN} — still emits diagnostics so dashboards keep showing impact, but does not
 *       enforce. This is the safe-roll mode for new fleets.
 *   <li>Switching modes at runtime takes effect on the next request — there is no cached decision.
 * </ul>
 */
class PulseRuntimeModeWiringTest {

    @Test
    void trace_guard_off_short_circuits_without_counters_or_rejection() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PulseRuntimeMode runtime = new PulseRuntimeMode(PulseRuntimeMode.Mode.OFF);
        TraceGuardFilter guard = new TraceGuardFilter(
                registry,
                new PulseProperties.TraceGuard(true, true, List.of(), PulseRequestMatcherProperties.empty()),
                PulseRequestMatcher.ALWAYS,
                runtime);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");

        guard.doFilter(request, new MockHttpServletResponse(), (req, resp) -> {});

        assertThat(registry.find("pulse.trace.missing").counter()).isNull();
        assertThat(registry.find("pulse.trace.received").counter()).isNull();
    }

    @Test
    void trace_guard_dry_run_observes_but_never_rejects() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PulseRuntimeMode runtime = new PulseRuntimeMode(PulseRuntimeMode.Mode.DRY_RUN);
        TraceGuardFilter guard = new TraceGuardFilter(
                registry,
                new PulseProperties.TraceGuard(true, true, List.of(), PulseRequestMatcherProperties.empty()),
                PulseRequestMatcher.ALWAYS,
                runtime);

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
        PulseRuntimeMode runtime = new PulseRuntimeMode(PulseRuntimeMode.Mode.ENFORCING);
        TraceGuardFilter guard = new TraceGuardFilter(
                registry,
                new PulseProperties.TraceGuard(true, true, List.of(), PulseRequestMatcherProperties.empty()),
                PulseRequestMatcher.ALWAYS,
                runtime);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");

        assertThatThrownBy(() -> guard.doFilter(request, new MockHttpServletResponse(), (req, resp) -> {}))
                .isInstanceOf(ServletException.class)
                .hasMessageContaining("missing trace-context");
    }

    @Test
    void trace_guard_runtime_change_takes_effect_on_next_request() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PulseRuntimeMode runtime = new PulseRuntimeMode(PulseRuntimeMode.Mode.ENFORCING);
        TraceGuardFilter guard = new TraceGuardFilter(
                registry,
                new PulseProperties.TraceGuard(true, true, List.of(), PulseRequestMatcherProperties.empty()),
                PulseRequestMatcher.ALWAYS,
                runtime);

        assertThatThrownBy(() -> guard.doFilter(
                        new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), (r, s) -> {}))
                .isInstanceOf(ServletException.class);

        runtime.set(PulseRuntimeMode.Mode.DRY_RUN);

        guard.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), (r, s) -> {});

        runtime.set(PulseRuntimeMode.Mode.OFF);

        guard.doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(), (r, s) -> {});
    }

    @Test
    void cardinality_firewall_off_lets_runaway_tags_through() {
        PulseProperties.Cardinality config = new PulseProperties.Cardinality(true, 5, "OVERFLOW", List.of(), List.of());
        PulseRuntimeMode runtime = new PulseRuntimeMode(PulseRuntimeMode.Mode.OFF);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new CardinalityFirewall(config, runtime, () -> registry));

        for (int i = 0; i < 50; i++) {
            registry.counter("orders.placed", "userId", "user-" + i).increment();
        }

        long distinct = registry.find("orders.placed").counters().stream()
                .map(c -> c.getId().getTag("userId"))
                .filter(v -> !"OVERFLOW".equals(v))
                .distinct()
                .count();
        assertThat(distinct).as("OFF mode short-circuits — no rewrites").isEqualTo(50);
        assertThat(registry.find("pulse.cardinality.overflow").counter())
                .as("OFF mode does not even count the would-have-clamped events")
                .isNull();
    }

    @Test
    void cardinality_firewall_dry_run_observes_overflow_but_does_not_clamp() {
        PulseProperties.Cardinality config = new PulseProperties.Cardinality(true, 5, "OVERFLOW", List.of(), List.of());
        PulseRuntimeMode runtime = new PulseRuntimeMode(PulseRuntimeMode.Mode.DRY_RUN);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CardinalityFirewall firewall = new CardinalityFirewall(config, runtime, () -> registry);
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
    void timeout_budget_filter_off_skips_baggage_installation() throws Exception {
        PulseProperties.TimeoutBudget config = new PulseProperties.TimeoutBudget(
                true,
                "Pulse-Timeout-Ms",
                "Pulse-Timeout-Ms",
                Duration.ofSeconds(2),
                Duration.ofSeconds(30),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                PulseRequestMatcherProperties.empty());
        PulseRuntimeMode runtime = new PulseRuntimeMode(PulseRuntimeMode.Mode.OFF);
        TimeoutBudgetFilter filter = new TimeoutBudgetFilter(config, PulseRequestMatcher.ALWAYS, runtime);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("Pulse-Timeout-Ms", "1000");
        AtomicBoolean budgetSeen = new AtomicBoolean(false);
        FilterChain chain =
                (req, resp) -> budgetSeen.set(TimeoutBudget.current().isPresent());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(budgetSeen)
                .as("OFF must skip baggage installation — downstream sees no deadline")
                .isFalse();
    }
}
