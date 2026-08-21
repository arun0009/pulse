package io.github.arun0009.pulse.guardrails;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeoutBudgetOutboundAbortTest {

    private static TimeoutBudgetProperties props(boolean abort) {
        return props(abort, false);
    }

    private static TimeoutBudgetProperties props(boolean abort, boolean applyClientTimeout) {
        return new TimeoutBudgetProperties(
                true,
                "Pulse-Timeout-Ms",
                "Pulse-Timeout-Ms",
                Duration.ofSeconds(2),
                Duration.ofSeconds(30),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                abort,
                applyClientTimeout,
                PulseRequestMatcherProperties.empty());
    }

    private static Scope openBudget(TimeoutBudget budget) {
        Baggage baggage = Baggage.builder()
                .put(TimeoutBudget.BAGGAGE_KEY, budget.toBaggageValue())
                .build();
        return baggage.storeInContext(Context.current()).makeCurrent();
    }

    @Test
    void default_stamps_exhausted_budget_and_does_not_abort() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TimeoutBudgetOutbound outbound = new TimeoutBudgetOutbound(registry, props(false));
        TimeoutBudget budget = TimeoutBudget.atDeadline(Instant.now().minusSeconds(1));

        try (Scope ignored = openBudget(budget)) {
            assertThat(outbound.remainingForOutbound("restclient")).contains(Duration.ZERO);
        }
        assertThat(registry.find(TimeoutBudgetOutbound.EXHAUSTED_COUNTER).counter())
                .isNotNull()
                .extracting(c -> c.count())
                .isEqualTo(1.0);
    }

    @Test
    void abort_on_exhaustion_throws_when_remaining_is_below_minimum() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TimeoutBudgetOutbound outbound = new TimeoutBudgetOutbound(registry, props(true));
        TimeoutBudget budget = TimeoutBudget.withRemaining(Duration.ofMillis(10));

        try (Scope ignored = openBudget(budget)) {
            assertThatThrownBy(() -> outbound.remainingForOutbound("resttemplate"))
                    .isInstanceOf(TimeoutBudgetExhaustedException.class)
                    .hasMessageContaining("transport=resttemplate");
        }
        assertThat(registry.find(TimeoutBudgetOutbound.EXHAUSTED_COUNTER).counter())
                .isNotNull()
                .extracting(c -> c.count())
                .isEqualTo(1.0);
    }

    @Test
    void kafka_resolve_remaining_never_throws_even_when_abort_is_on() {
        TimeoutBudgetOutbound outbound = new TimeoutBudgetOutbound(new SimpleMeterRegistry(), props(true));
        TimeoutBudget budget = TimeoutBudget.atDeadline(Instant.now().minusSeconds(1));

        try (Scope ignored = openBudget(budget)) {
            assertThat(outbound.resolveRemaining("kafka")).contains(Duration.ZERO);
        }
    }

    @Test
    void apply_client_timeout_off_returns_empty_even_with_remaining_budget() {
        TimeoutBudgetOutbound outbound = new TimeoutBudgetOutbound(new SimpleMeterRegistry(), props(false, false));
        TimeoutBudget budget = TimeoutBudget.withRemaining(Duration.ofSeconds(1));

        try (Scope ignored = openBudget(budget)) {
            assertThat(outbound.applyClientTimeout()).isFalse();
            assertThat(outbound.remainingForClientTimeout()).isEmpty();
        }
    }

    @Test
    void apply_client_timeout_on_returns_remaining_when_positive() {
        TimeoutBudgetOutbound outbound = new TimeoutBudgetOutbound(new SimpleMeterRegistry(), props(false, true));
        TimeoutBudget budget = TimeoutBudget.withRemaining(Duration.ofMillis(750));

        try (Scope ignored = openBudget(budget)) {
            assertThat(outbound.applyClientTimeout()).isTrue();
            Duration remaining = outbound.remainingForClientTimeout().orElseThrow();
            assertThat(remaining).isLessThanOrEqualTo(Duration.ofMillis(750)).isGreaterThan(Duration.ofMillis(500));
        }
    }

    @Test
    void apply_client_timeout_on_skips_zero_remaining() {
        TimeoutBudgetOutbound outbound = new TimeoutBudgetOutbound(new SimpleMeterRegistry(), props(false, true));
        TimeoutBudget budget = TimeoutBudget.atDeadline(Instant.now().minusSeconds(1));

        try (Scope ignored = openBudget(budget)) {
            assertThat(outbound.remainingForClientTimeout()).isEmpty();
        }
    }
}
