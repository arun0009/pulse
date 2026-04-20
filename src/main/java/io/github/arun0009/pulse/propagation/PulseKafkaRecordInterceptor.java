package io.github.arun0009.pulse.propagation;

import io.github.arun0009.pulse.core.ContextProperties;
import io.github.arun0009.pulse.core.LogSanitizer;
import io.github.arun0009.pulse.guardrails.TimeoutBudget;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetProperties;
import io.github.arun0009.pulse.priority.PriorityProperties;
import io.github.arun0009.pulse.resilience.RetryProperties;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Scope;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Spring Kafka {@link RecordInterceptor} that performs the inverse of {@link
 * PulseKafkaProducerInterceptor} on the consumer side: hydrates MDC from record headers and opens
 * a {@link TimeoutBudget} baggage scope so that any code reached from the {@code @KafkaListener}
 * method observes the originating caller's deadline.
 *
 * <p>Cleanup happens in {@link #afterRecord(ConsumerRecord, Consumer)} which fires after both the
 * success and failure paths. Per-record state is kept in thread-locals (one record at a time per
 * listener thread by Spring Kafka contract).
 */
public class PulseKafkaRecordInterceptor implements RecordInterceptor<Object, Object> {

    private static final Logger log = LoggerFactory.getLogger(PulseKafkaRecordInterceptor.class);

    private static final ThreadLocal<Set<String>> MDC_KEYS_PUT = ThreadLocal.withInitial(HashSet::new);
    private static final ThreadLocal<@Nullable Scope> BAGGAGE_SCOPE = new ThreadLocal<>();

    private final Map<String, String> headerToMdcKey;
    private final String timeoutBudgetHeader;
    private final boolean timeoutBudgetEnabled;
    private final @Nullable KafkaConsumerTimeLagMetrics timeLagMetrics;

    public PulseKafkaRecordInterceptor(
            ContextProperties context,
            RetryProperties retry,
            PriorityProperties priority,
            TimeoutBudgetProperties timeoutBudget) {
        this(context, retry, priority, timeoutBudget, null);
    }

    public PulseKafkaRecordInterceptor(
            ContextProperties context,
            RetryProperties retry,
            PriorityProperties priority,
            TimeoutBudgetProperties timeoutBudget,
            @Nullable KafkaConsumerTimeLagMetrics timeLagMetrics) {
        this.headerToMdcKey = HeaderPropagation.headerToMdcKey(context, retry, priority);
        this.timeoutBudgetHeader = timeoutBudget.outboundHeader();
        this.timeoutBudgetEnabled = timeoutBudget.enabled();
        this.timeLagMetrics = timeLagMetrics;
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(
            ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        try {
            Set<String> putKeys = MDC_KEYS_PUT.get();
            headerToMdcKey.forEach((header, mdcKey) -> {
                Header h = record.headers().lastHeader(header);
                if (h != null && h.value() != null && MDC.get(mdcKey) == null) {
                    MDC.put(mdcKey, new String(h.value(), StandardCharsets.UTF_8));
                    putKeys.add(mdcKey);
                }
            });

            if (timeoutBudgetEnabled) {
                Header budgetHeader = record.headers().lastHeader(timeoutBudgetHeader);
                if (budgetHeader != null && budgetHeader.value() != null) {
                    activateBudgetScope(new String(budgetHeader.value(), StandardCharsets.UTF_8));
                }
            }

            if (timeLagMetrics != null) {
                String groupId;
                try {
                    groupId = consumer.groupMetadata().groupId();
                } catch (RuntimeException ignored) {
                    groupId = KafkaConsumerTimeLagMetrics.UNKNOWN_GROUP;
                }
                timeLagMetrics.observe(record, groupId);
            }
        } catch (RuntimeException e) {
            log.debug("Pulse Kafka record interceptor: header hydration failed", e);
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        Set<String> putKeys = MDC_KEYS_PUT.get();
        try {
            putKeys.forEach(MDC::remove);
        } finally {
            putKeys.clear();
            Scope scope = BAGGAGE_SCOPE.get();
            if (scope != null) {
                try {
                    scope.close();
                } catch (RuntimeException ignored) {
                    // Best effort.
                } finally {
                    BAGGAGE_SCOPE.remove();
                }
            }
        }
    }

    @SuppressWarnings("MustBeClosedChecker") // Scope is closed in afterRecord — owned across hooks.
    private void activateBudgetScope(String headerValue) {
        try {
            long remainingMs = Long.parseLong(headerValue.trim());
            if (remainingMs < 0) {
                return;
            }
            TimeoutBudget budget = TimeoutBudget.withRemaining(Duration.ofMillis(remainingMs));
            Scope scope = Baggage.current().toBuilder()
                    .put(TimeoutBudget.BAGGAGE_KEY, budget.toBaggageValue())
                    .build()
                    .makeCurrent();
            BAGGAGE_SCOPE.set(scope);
        } catch (NumberFormatException e) {
            log.debug("Pulse Kafka: malformed timeout-budget header value '{}'", LogSanitizer.safe(headerValue));
        }
    }
}
