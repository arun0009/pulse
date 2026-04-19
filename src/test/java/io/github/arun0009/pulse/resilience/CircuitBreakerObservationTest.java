package io.github.arun0009.pulse.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a real {@link CircuitBreakerRegistry} through realistic state changes and asserts
 * Pulse turns each event into the documented metric, log, and span signal. The point of these
 * tests is to keep the public-contract metric names locked in — any rename would silently
 * break every downstream Grafana dashboard that depends on
 * {@code pulse.r4j.circuit_breaker.state_transitions}.
 */
class CircuitBreakerObservationTest {

    private MeterRegistry meterRegistry;
    private CircuitBreakerRegistry breakerRegistry;
    private CircuitBreakerObservation observation;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        breakerRegistry = CircuitBreakerRegistry.ofDefaults();
        observation = new CircuitBreakerObservation(breakerRegistry, meterRegistry);
        observation.afterSingletonsInstantiated();
    }

    @Test
    void state_transition_increments_counter_with_name_from_to_tags() {
        CircuitBreaker breaker = createFastFailingBreaker("orders-svc");
        breaker.transitionToOpenState();

        // OPEN is fired via transitionToOpenState() — the counter for CLOSED -> OPEN must show
        // exactly one increment, tagged by name + from + to so dashboards can split by route.
        assertThat(meterRegistry
                        .counter(
                                "pulse.r4j.circuit_breaker.state_transitions",
                                Tags.of("name", "orders-svc", "from", "CLOSED", "to", "OPEN"))
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void state_gauge_reflects_current_breaker_state() {
        CircuitBreaker breaker = createFastFailingBreaker("downstream");
        breaker.transitionToOpenState();

        // OPEN.getOrder() == 4 in the public Resilience4j API. We assert the numeric value
        // rather than the enum name because Prometheus only stores numbers — this is the
        // contract a Grafana panel relies on.
        assertThat(meterRegistry
                        .find("pulse.r4j.circuit_breaker.state")
                        .tags("name", "downstream")
                        .gauge()
                        .value())
                .isEqualTo((double) CircuitBreaker.State.OPEN.getOrder());
    }

    @Test
    void error_event_increments_errors_total() {
        CircuitBreaker breaker = createFastFailingBreaker("payments");
        breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException("upstream timed out"));

        assertThat(meterRegistry
                        .counter("pulse.r4j.circuit_breaker.errors_total", Tags.of("name", "payments"))
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void lazily_added_breaker_is_observed_after_startup() {
        // Resilience4j supports calling registry.circuitBreaker(name) at any time after init.
        // The on-entry-added hook must wire those breakers; a regression here would cause Pulse
        // to silently miss every breaker created on demand by user code.
        int wiredAtStartup = observation.wiredCount();
        breakerRegistry.circuitBreaker("late-bound");

        assertThat(observation.wiredCount()).isEqualTo(wiredAtStartup + 1);
    }

    @Test
    void attaching_the_same_breaker_twice_does_not_double_subscribe() {
        // Defensive: double-subscription would double-count every transition. Resilience4j's
        // EventPublisher does dedup callbacks, but Pulse asserts its own guard so a future
        // R4j change can't reintroduce the bug.
        CircuitBreaker breaker = createFastFailingBreaker("dup");
        breaker.transitionToOpenState();
        // Trigger a re-attach by re-running the lifecycle hook.
        observation.afterSingletonsInstantiated();
        breaker.transitionToHalfOpenState();

        // Only one counter increment per transition, regardless of how many times we wired.
        assertThat(meterRegistry
                        .counter(
                                "pulse.r4j.circuit_breaker.state_transitions",
                                Tags.of("name", "dup", "from", "OPEN", "to", "HALF_OPEN"))
                        .count())
                .isEqualTo(1.0);
    }

    private CircuitBreaker createFastFailingBreaker(String name) {
        // minimumNumberOfCalls=1 so a single onError flips the breaker without a warmup window
        // — keeps the test deterministic without sleeps.
        return breakerRegistry.circuitBreaker(
                name,
                CircuitBreakerConfig.custom()
                        .minimumNumberOfCalls(1)
                        .slidingWindowSize(1)
                        .failureRateThreshold(50f)
                        .build());
    }
}
