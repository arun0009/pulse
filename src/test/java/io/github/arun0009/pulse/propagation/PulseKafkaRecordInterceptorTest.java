package io.github.arun0009.pulse.propagation;

import io.github.arun0009.pulse.core.ContextKeys;
import io.github.arun0009.pulse.core.ContextProperties;
import io.github.arun0009.pulse.guardrails.TimeoutBudget;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetProperties;
import io.github.arun0009.pulse.priority.PriorityProperties;
import io.github.arun0009.pulse.resilience.RetryProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior of the consumer-side propagation interceptor: incoming Kafka headers must hydrate the
 * listener thread's MDC and the timeout budget so business logic observes the same context the
 * upstream producer had.
 */
class PulseKafkaRecordInterceptorTest {

    private final PulseKafkaRecordInterceptor interceptor = new PulseKafkaRecordInterceptor(
            defaultContext(), defaultRetry(), defaultPriority(), defaultTimeoutBudget());

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    void mdc_is_hydrated_from_record_headers() {
        ConsumerRecord<Object, Object> record = consumerRecord();
        record.headers().add("X-Request-ID", "req-99".getBytes(StandardCharsets.UTF_8));
        record.headers().add("X-User-ID", "user-1".getBytes(StandardCharsets.UTF_8));

        interceptor.intercept(record, /* consumer */ null);

        assertThat(MDC.get(ContextKeys.REQUEST_ID)).isEqualTo("req-99");
        assertThat(MDC.get(ContextKeys.USER_ID)).isEqualTo("user-1");
    }

    @Test
    void afterRecord_clears_only_the_keys_the_interceptor_added() {
        MDC.put("application.added", "do-not-touch");

        ConsumerRecord<Object, Object> record = consumerRecord();
        record.headers().add("X-Request-ID", "req-99".getBytes(StandardCharsets.UTF_8));

        interceptor.intercept(record, /* consumer */ null);
        interceptor.afterRecord(record, /* consumer */ null);

        assertThat(MDC.get(ContextKeys.REQUEST_ID)).isNull();
        assertThat(MDC.get("application.added")).isEqualTo("do-not-touch");
    }

    @Test
    void existing_mdc_is_not_overwritten_by_incoming_headers() {
        MDC.put(ContextKeys.REQUEST_ID, "already-set");

        ConsumerRecord<Object, Object> record = consumerRecord();
        record.headers().add("X-Request-ID", "from-header".getBytes(StandardCharsets.UTF_8));

        interceptor.intercept(record, /* consumer */ null);

        assertThat(MDC.get(ContextKeys.REQUEST_ID)).isEqualTo("already-set");
    }

    @Test
    void timeout_budget_header_opens_baggage_scope_visible_to_business_logic() {
        ConsumerRecord<Object, Object> record = consumerRecord();
        record.headers().add("Pulse-Timeout-Ms", "1500".getBytes(StandardCharsets.UTF_8));

        interceptor.intercept(record, /* consumer */ null);
        try {
            Optional<TimeoutBudget> budget = TimeoutBudget.current();
            assertThat(budget).isPresent();
            assertThat(budget.get().remaining())
                    .isLessThanOrEqualTo(Duration.ofMillis(1500))
                    .isGreaterThan(Duration.ofMillis(1000));
        } finally {
            interceptor.afterRecord(record, /* consumer */ null);
        }

        assertThat(TimeoutBudget.current()).isEmpty();
    }

    @Test
    void malformed_timeout_header_is_silently_ignored() {
        ConsumerRecord<Object, Object> record = consumerRecord();
        record.headers().add("Pulse-Timeout-Ms", "not-a-number".getBytes(StandardCharsets.UTF_8));

        interceptor.intercept(record, /* consumer */ null);
        try {
            assertThat(TimeoutBudget.current()).isEmpty();
        } finally {
            interceptor.afterRecord(record, /* consumer */ null);
        }
    }

    private static ConsumerRecord<Object, Object> consumerRecord() {
        return new ConsumerRecord<>("orders", 0, 0L, "key", "value");
    }

    static ContextProperties defaultContext() {
        return new ContextProperties(
                true, "X-Request-ID", "X-Correlation-ID", "X-User-ID", "Pulse-Tenant-Id", "Idempotency-Key", List.of());
    }

    static RetryProperties defaultRetry() {
        return new RetryProperties(true, "Pulse-Retry-Depth", 3);
    }

    static PriorityProperties defaultPriority() {
        return new PriorityProperties(true, "Pulse-Priority", "normal", true, List.of());
    }

    static TimeoutBudgetProperties defaultTimeoutBudget() {
        return new TimeoutBudgetProperties(
                true,
                "Pulse-Timeout-Ms",
                "Pulse-Timeout-Ms",
                Duration.ofSeconds(2),
                Duration.ofSeconds(30),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties.empty());
    }
}
