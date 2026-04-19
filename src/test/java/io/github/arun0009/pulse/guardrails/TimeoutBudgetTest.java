package io.github.arun0009.pulse.guardrails;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior of the in-process timeout budget: deadline arithmetic must hold across baggage
 * round-trips, and {@link TimeoutBudget#current()} must reflect what an inbound filter would have
 * placed on the OTel context.
 */
class TimeoutBudgetTest {

    @Test
    void remaining_decreases_monotonically_until_zero() throws InterruptedException {
        TimeoutBudget budget = TimeoutBudget.withRemaining(Duration.ofMillis(150));
        Duration first = budget.remaining();
        Thread.sleep(50);
        Duration second = budget.remaining();
        assertThat(second).isLessThan(first);
        assertThat(budget.expired()).isFalse();
    }

    @Test
    void expired_budget_reports_zero_remaining() {
        TimeoutBudget budget = TimeoutBudget.atDeadline(Instant.now().minusSeconds(1));
        assertThat(budget.remaining()).isEqualTo(Duration.ZERO);
        assertThat(budget.expired()).isTrue();
    }

    @Test
    void baggage_round_trip_preserves_deadline_to_the_millisecond() {
        TimeoutBudget original = TimeoutBudget.withRemaining(Duration.ofMillis(2500));
        String wireValue = original.toBaggageValue();

        Optional<TimeoutBudget> reparsed = TimeoutBudget.parse(wireValue);
        assertThat(reparsed).isPresent();
        assertThat(reparsed.get().deadline().toEpochMilli())
                .isEqualTo(original.deadline().toEpochMilli());
    }

    @Test
    void current_reads_from_baggage_when_filter_has_run_upstream() {
        TimeoutBudget toStash = TimeoutBudget.withRemaining(Duration.ofSeconds(3));
        Baggage baggage = Baggage.builder()
                .put(TimeoutBudget.BAGGAGE_KEY, toStash.toBaggageValue())
                .build();

        try (Scope ignored = baggage.storeInContext(Context.current()).makeCurrent()) {
            Optional<TimeoutBudget> readBack = TimeoutBudget.current();
            assertThat(readBack).isPresent();
            assertThat(readBack.get().remaining())
                    .isLessThanOrEqualTo(Duration.ofSeconds(3))
                    .isGreaterThan(Duration.ofMillis(2500));
        }
    }

    @Test
    void current_is_empty_outside_a_pulse_managed_request() {
        assertThat(TimeoutBudget.current()).isEmpty();
    }

    @Test
    void malformed_baggage_value_is_silently_ignored() {
        assertThat(TimeoutBudget.parse(null)).isEmpty();
        assertThat(TimeoutBudget.parse("")).isEmpty();
        assertThat(TimeoutBudget.parse("not-a-number")).isEmpty();
    }
}
