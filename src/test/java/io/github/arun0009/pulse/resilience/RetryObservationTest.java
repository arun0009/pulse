package io.github.arun0009.pulse.resilience;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives a {@link Retry} through real attempts and exhausting flows. Each test validates one
 * Pulse invariant — attempts increment, exhaustion increments, names propagate as tags — so a
 * regression in the event-binding glue surfaces immediately.
 */
class RetryObservationTest {

    private MeterRegistry meterRegistry;
    private RetryRegistry retryRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        retryRegistry = RetryRegistry.ofDefaults();
        new RetryObservation(retryRegistry, meterRegistry).afterSingletonsInstantiated();
    }

    @Test
    void each_retry_attempt_after_the_first_increments_attempts_total() {
        // Resilience4j fires onRetry for every attempt that is *retried* — i.e. all attempts
        // after the initial call. With max=3 attempts and a perpetually-failing supplier we
        // expect 2 retry events (attempts 2 and 3).
        Retry retry = retryRegistry.retry(
                "upstream-flaky",
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofMillis(1))
                        .retryExceptions(RuntimeException.class)
                        .build());

        AtomicInteger calls = new AtomicInteger();
        Runnable alwaysFails = () -> {
            calls.incrementAndGet();
            throw new RuntimeException("nope");
        };

        assertThatThrownBy(() -> retry.executeRunnable(alwaysFails)).isInstanceOf(RuntimeException.class);

        assertThat(calls.get()).isEqualTo(3);
        assertThat(meterRegistry
                        .counter("pulse.resilience.retry.attempts", Tags.of("name", "upstream-flaky"))
                        .count())
                .isEqualTo(2.0);
    }

    @Test
    void final_exhaustion_increments_exhausted_total() {
        Retry retry = retryRegistry.retry(
                "exhauster",
                RetryConfig.custom()
                        .maxAttempts(2)
                        .waitDuration(Duration.ofMillis(1))
                        .retryExceptions(RuntimeException.class)
                        .build());

        assertThatThrownBy(() -> retry.executeRunnable(() -> {
                    throw new RuntimeException("nope");
                }))
                .isInstanceOf(RuntimeException.class);

        assertThat(meterRegistry
                        .counter("pulse.resilience.retry.exhausted", Tags.of("name", "exhauster"))
                        .count())
                .as("Exhaustion is the operationally meaningful signal — counting it lets SREs"
                        + " alert on retry-budget burn rather than just retry attempts")
                .isEqualTo(1.0);
    }

    @Test
    void successful_call_with_no_retries_emits_neither_metric() {
        Retry retry = retryRegistry.retry("happy-path");
        retry.executeRunnable(() -> {});

        assertThat(meterRegistry.find("pulse.resilience.retry.attempts").counter())
                .isNull();
        assertThat(meterRegistry.find("pulse.resilience.retry.exhausted").counter())
                .isNull();
    }
}
