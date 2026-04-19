package io.github.arun0009.pulse.propagation;

import io.github.arun0009.pulse.guardrails.TimeoutBudgetOutbound;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Kafka-native producer interceptor that copies the calling thread's MDC context and remaining
 * timeout budget onto outbound record headers so a downstream consumer (Pulse-equipped or not)
 * can correlate the message with the originating request.
 *
 * <p>Kafka instantiates this interceptor with a no-arg constructor and one instance per producer,
 * so configuration is read from {@link KafkaPropagationContext}, populated at Spring startup.
 *
 * <p>Trace context (W3C {@code traceparent}) is already propagated by Spring Boot's OpenTelemetry
 * integration (Micrometer Observation + OTel Kafka instrumentation); this interceptor only adds
 * the application-level headers and timeout budget that those layers do not handle.
 */
public class PulseKafkaProducerInterceptor implements ProducerInterceptor<Object, Object> {

    private static final Logger log = LoggerFactory.getLogger(PulseKafkaProducerInterceptor.class);

    @Override
    public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
        if (!KafkaPropagationContext.initialized()) {
            return record;
        }
        try {
            Headers headers = record.headers();

            Map<String, String> headerMap = KafkaPropagationContext.headerToMdcKey();
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            if (mdc != null) {
                headerMap.forEach((header, mdcKey) -> {
                    String value = mdc.get(mdcKey);
                    if (value != null && headers.lastHeader(header) == null) {
                        headers.add(header, value.getBytes(StandardCharsets.UTF_8));
                    }
                });
            }

            String budgetHeader = KafkaPropagationContext.timeoutBudgetHeader();
            if (headers.lastHeader(budgetHeader) == null) {
                TimeoutBudgetOutbound budgetHelper = KafkaPropagationContext.budgetHelper();
                if (budgetHelper != null) {
                    budgetHelper
                            .resolveRemaining("kafka")
                            .ifPresent(remaining -> headers.add(
                                    budgetHeader,
                                    Long.toString(remaining.toMillis()).getBytes(StandardCharsets.UTF_8)));
                }
            }
        } catch (RuntimeException e) {
            // Never fail a send because of observability.
            log.debug("Pulse Kafka producer interceptor: header propagation failed", e);
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        // No-op.
    }

    @Override
    public void close() {
        // No-op.
    }

    @Override
    public void configure(Map<String, ?> configs) {
        // No-op — config arrives via KafkaPropagationContext at Spring startup.
    }
}
