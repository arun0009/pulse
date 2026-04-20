package io.github.arun0009.pulse.guardrails;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import io.github.arun0009.pulse.core.PulseRequestMatcher;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the 1.1 {@code enabled-when} gate on {@link TimeoutBudgetFilter}: when the matcher
 * rejects a request the filter must not establish a budget (so {@link TimeoutBudget#current()}
 * stays empty downstream) and the chain must still run.
 */
class TimeoutBudgetFilterEnabledWhenTest {

    private static final TimeoutBudgetProperties CONFIG = new TimeoutBudgetProperties(
            true,
            "Pulse-Timeout-Ms",
            "Pulse-Timeout-Ms",
            Duration.ofSeconds(2),
            Duration.ofSeconds(30),
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            PulseRequestMatcherProperties.empty());

    @Test
    void matcher_rejects_request_skips_budget_but_chain_still_runs() throws Exception {
        TimeoutBudgetFilter filter =
                new TimeoutBudgetFilter(CONFIG, request -> !"test-client".equals(request.getHeader("client-id")));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("client-id", "test-client");
        AtomicBoolean budgetSeen = new AtomicBoolean(false);
        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        FilterChain chain = (req, resp) -> {
            budgetSeen.set(TimeoutBudget.current().isPresent());
            chainInvoked.set(true);
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chainInvoked).isTrue();
        assertThat(budgetSeen)
                .as("when matcher rejects, no synthetic budget should leak into downstream code")
                .isFalse();
    }

    @Test
    void matcher_accepts_request_still_establishes_budget() throws Exception {
        TimeoutBudgetFilter filter = new TimeoutBudgetFilter(CONFIG, PulseRequestMatcher.ALWAYS);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        AtomicBoolean budgetSeen = new AtomicBoolean(false);
        FilterChain chain =
                (req, resp) -> budgetSeen.set(TimeoutBudget.current().isPresent());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(budgetSeen)
                .as("matcher accepted; the budget should still be established as in pre-1.1 behaviour")
                .isTrue();
    }
}
