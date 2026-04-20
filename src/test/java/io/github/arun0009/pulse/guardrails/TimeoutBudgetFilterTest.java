package io.github.arun0009.pulse.guardrails;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter is the entry point that establishes a request's deadline. Tests cover (1) inbound
 * header is honored, (2) absent header falls back to default, (3) the safety margin is subtracted,
 * (4) a tiny inbound budget is floored at the configured minimum, (5) downstream code reads the
 * budget via {@link TimeoutBudget#current()} during the chain.
 */
class TimeoutBudgetFilterTest {

    private static final TimeoutBudgetProperties CONFIG = new TimeoutBudgetProperties(
            true,
            "Pulse-Timeout-Ms",
            "Pulse-Timeout-Ms",
            Duration.ofSeconds(2),
            Duration.ofSeconds(30),
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            PulseRequestMatcherProperties.empty());

    private final TimeoutBudgetFilter filter = new TimeoutBudgetFilter(CONFIG);

    @Test
    void inbound_header_is_honored_minus_safety_margin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("Pulse-Timeout-Ms", "1000");
        AtomicReference<Duration> observed = new AtomicReference<>();

        FilterChain chain = (req, resp) ->
                observed.set(TimeoutBudget.current().orElseThrow().remaining());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(observed.get()).isLessThanOrEqualTo(Duration.ofMillis(950)).isGreaterThan(Duration.ofMillis(800));
    }

    @Test
    void absent_inbound_header_falls_back_to_default_budget() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        AtomicReference<Duration> observed = new AtomicReference<>();

        FilterChain chain = (req, resp) ->
                observed.set(TimeoutBudget.current().orElseThrow().remaining());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(observed.get()).isLessThanOrEqualTo(Duration.ofMillis(1950)).isGreaterThan(Duration.ofMillis(1800));
    }

    @Test
    void tiny_inbound_budget_is_floored_at_minimum() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("Pulse-Timeout-Ms", "10");
        AtomicReference<Duration> observed = new AtomicReference<>();

        FilterChain chain = (req, resp) ->
                observed.set(TimeoutBudget.current().orElseThrow().remaining());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(observed.get()).isGreaterThanOrEqualTo(Duration.ofMillis(95));
    }

    @Test
    void inbound_budget_above_maximum_is_clamped() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("Pulse-Timeout-Ms", "120000");
        AtomicReference<Duration> observed = new AtomicReference<>();

        FilterChain chain = (req, resp) ->
                observed.set(TimeoutBudget.current().orElseThrow().remaining());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(observed.get()).isLessThanOrEqualTo(Duration.ofMillis(29950)).isGreaterThan(Duration.ofSeconds(25));
    }

    @Test
    void zero_default_with_no_inbound_header_skips_baggage_entirely() throws Exception {
        // Operators can opt out of the implicit safety net by setting defaultBudget to 0.
        // When no inbound header arrives, downstream code sees TimeoutBudget.current() == empty
        // — i.e., no fabricated deadline. This is the contract the showcase relies on.
        TimeoutBudgetFilter optOut = new TimeoutBudgetFilter(new TimeoutBudgetProperties(
                true,
                "Pulse-Timeout-Ms",
                "Pulse-Timeout-Ms",
                Duration.ZERO,
                Duration.ofSeconds(30),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                PulseRequestMatcherProperties.empty()));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        AtomicReference<Boolean> observed = new AtomicReference<>();

        FilterChain chain = (req, resp) -> observed.set(TimeoutBudget.current().isPresent());

        optOut.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(observed.get()).isFalse();
    }

    @Test
    void zero_default_still_honors_explicit_inbound_header() throws Exception {
        // Opt-out only suppresses the *implicit* default; an explicit caller-provided header
        // must still propagate.
        TimeoutBudgetFilter optOut = new TimeoutBudgetFilter(new TimeoutBudgetProperties(
                true,
                "Pulse-Timeout-Ms",
                "Pulse-Timeout-Ms",
                Duration.ZERO,
                Duration.ofSeconds(30),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                PulseRequestMatcherProperties.empty()));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("Pulse-Timeout-Ms", "1500");
        AtomicReference<Duration> observed = new AtomicReference<>();

        FilterChain chain = (req, resp) ->
                observed.set(TimeoutBudget.current().orElseThrow().remaining());

        optOut.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(observed.get()).isLessThanOrEqualTo(Duration.ofMillis(1450)).isGreaterThan(Duration.ofMillis(1300));
    }

    @Test
    void after_filter_returns_baggage_no_longer_carries_the_deadline() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        FilterChain chain = (req, resp) -> {
            /* noop */
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(TimeoutBudget.current())
                .as("scope must be torn down so caller's context is unaffected")
                .isEmpty();
    }
}
