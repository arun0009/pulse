package io.github.arun0009.pulse.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asserts that bulkhead rejections — which are by definition load-shedding events the operator
 * needs to see — are translated to a counter and a structured WARN line.
 */
class BulkheadObservationTest {

    private MeterRegistry meterRegistry;
    private BulkheadRegistry bulkheadRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        bulkheadRegistry = BulkheadRegistry.ofDefaults();
        new BulkheadObservation(bulkheadRegistry, meterRegistry).afterSingletonsInstantiated();
    }

    @Test
    void rejected_call_increments_rejected_total_with_bulkhead_name_tag() {
        // Bulkhead with capacity=1 and zero wait time: the second concurrent call is rejected
        // immediately with BulkheadFullException — that's the operationally important signal.
        Bulkhead tinyBulkhead = bulkheadRegistry.bulkhead(
                "downstream",
                BulkheadConfig.custom()
                        .maxConcurrentCalls(1)
                        .maxWaitDuration(Duration.ZERO)
                        .build());

        // Acquire the only permit and hold it.
        boolean acquired = tinyBulkhead.tryAcquirePermission();
        assertThat(acquired).isTrue();

        try {
            assertThatThrownBy(() -> tinyBulkhead.executeRunnable(() -> {})).isInstanceOf(BulkheadFullException.class);

            assertThat(meterRegistry
                            .counter("pulse.r4j.bulkhead.rejected_total", Tags.of("name", "downstream"))
                            .count())
                    .isEqualTo(1.0);
        } finally {
            tinyBulkhead.releasePermission();
        }
    }
}
