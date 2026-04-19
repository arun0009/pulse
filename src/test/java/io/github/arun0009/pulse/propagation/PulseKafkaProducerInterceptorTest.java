package io.github.arun0009.pulse.propagation;

import io.github.arun0009.pulse.core.ContextKeys;
import io.github.arun0009.pulse.guardrails.TimeoutBudget;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior of the producer-side propagation interceptor: MDC and timeout-budget must land on
 * outbound Kafka record headers without clobbering whatever the application already set.
 */
class PulseKafkaProducerInterceptorTest {

    private final PulseKafkaProducerInterceptor interceptor = new PulseKafkaProducerInterceptor();

    @BeforeEach
    void initContext() {
        KafkaPropagationContext.resetForTesting(
                Map.of(
                        "X-Request-ID", ContextKeys.REQUEST_ID,
                        "X-User-ID", ContextKeys.USER_ID),
                "Pulse-Timeout-Ms");
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
        KafkaPropagationContext.resetForTesting(null, null);
    }

    @Test
    void mdc_values_are_copied_onto_outbound_record_headers() {
        MDC.put(ContextKeys.REQUEST_ID, "req-42");
        MDC.put(ContextKeys.USER_ID, "user-7");

        ProducerRecord<Object, Object> record = new ProducerRecord<>("orders", "payload");
        ProducerRecord<Object, Object> result = interceptor.onSend(record);

        assertThat(headerValue(result, "X-Request-ID")).isEqualTo("req-42");
        assertThat(headerValue(result, "X-User-ID")).isEqualTo("user-7");
    }

    @Test
    void existing_headers_are_not_overwritten() {
        MDC.put(ContextKeys.REQUEST_ID, "from-mdc");

        ProducerRecord<Object, Object> record = new ProducerRecord<>("orders", "payload");
        record.headers().add("X-Request-ID", "from-application".getBytes(StandardCharsets.UTF_8));

        interceptor.onSend(record);

        assertThat(headerValue(record, "X-Request-ID")).isEqualTo("from-application");
    }

    @Test
    void remaining_timeout_budget_is_propagated_as_header() {
        Baggage baggage = Baggage.builder()
                .put(
                        TimeoutBudget.BAGGAGE_KEY,
                        TimeoutBudget.withRemaining(Duration.ofSeconds(2)).toBaggageValue())
                .build();

        try (Scope ignored = baggage.storeInContext(Context.current()).makeCurrent()) {
            ProducerRecord<Object, Object> record = new ProducerRecord<>("orders", "payload");
            interceptor.onSend(record);

            String budgetHeader = headerValue(record, "Pulse-Timeout-Ms");
            long remainingMs;
            try {
                remainingMs = Long.parseLong(budgetHeader);
            } catch (NumberFormatException e) {
                throw new AssertionError("Pulse-Timeout-Ms must be a long, got: " + budgetHeader, e);
            }
            assertThat(remainingMs).isBetween(1500L, 2000L);
        }
    }

    @Test
    void no_baggage_means_no_timeout_header_added() {
        ProducerRecord<Object, Object> record = new ProducerRecord<>("orders", "payload");
        interceptor.onSend(record);

        assertThat(record.headers().lastHeader("Pulse-Timeout-Ms")).isNull();
    }

    @Test
    void interceptor_is_a_no_op_until_spring_initializes_the_context() {
        KafkaPropagationContext.resetForTesting(null, null);
        MDC.put(ContextKeys.REQUEST_ID, "req-42");

        ProducerRecord<Object, Object> record = new ProducerRecord<>("orders", "payload");
        interceptor.onSend(record);

        assertThat(record.headers().lastHeader("X-Request-ID")).isNull();
    }

    private static String headerValue(ProducerRecord<Object, Object> record, String name) {
        Header h = record.headers().lastHeader(name);
        assertThat(h).as("header %s on record", name).isNotNull();
        return new String(h.value(), StandardCharsets.UTF_8);
    }
}
