package io.github.arun0009.pulse.propagation;

import io.github.arun0009.pulse.core.ContextProperties;
import io.github.arun0009.pulse.core.LogSanitizer;
import io.github.arun0009.pulse.guardrails.TimeoutBudget;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetProperties;
import io.github.arun0009.pulse.priority.PriorityProperties;
import io.github.arun0009.pulse.resilience.RetryProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.Optional;
import java.util.Set;

/**
 * Spring Kafka {@link RecordInterceptor} that performs the inverse of {@link
 * PulseKafkaProducerInterceptor} on the consumer side: hydrates MDC from record headers and opens
 * a {@link TimeoutBudget} baggage scope so that any code reached from the {@code @KafkaListener}
 * method observes the originating caller's deadline.
 *
 * <p>The RPC deadline is reconstructed from {@link TimeoutBudget#KAFKA_DEADLINE_HEADER} (absolute
 * epoch-millis stamped at produce time). A remaining-ms header restarting at consume time would
 * give the listener a fresh budget after the message sat in the topic. Kafka record timestamps
 * are not used for that clock — they are often event-time, not produce wall-clock. Legacy records
 * with only {@code Pulse-Timeout-Ms} fall back to {@link TimeoutBudget#withRemaining(Duration)}.
 *
 * <p>The event-freshness clock is separate and opt-in via
 * {@link KafkaPropagationProperties#skipStaleRecords()}. Returning {@code null} drops the record
 * before the listener runs; Pulse does <em>not</em> skip just because the HTTP deadline expired.
 * A 202 Accepted publisher must still process "charge the card later".
 *
 * <p>Cleanup happens in {@link #afterRecord(ConsumerRecord, Consumer)} which fires after both the
 * success and failure paths. Per-record state is kept in thread-locals (one record at a time per
 * listener thread by Spring Kafka contract). Stale skips hydrate nothing, so a missing
 * {@code afterRecord} on the skip path cannot leak MDC or baggage.
 */
public class PulseKafkaRecordInterceptor implements RecordInterceptor<Object, Object> {

    public static final String STALE_SKIPPED_COUNTER = "pulse.kafka.stale_skipped";

    private static final Logger log = LoggerFactory.getLogger(PulseKafkaRecordInterceptor.class);

    private static final ThreadLocal<Set<String>> MDC_KEYS_PUT = ThreadLocal.withInitial(HashSet::new);
    private static final ThreadLocal<@Nullable Scope> BAGGAGE_SCOPE = new ThreadLocal<>();

    private final Map<String, String> headerToMdcKey;
    private final String timeoutBudgetHeader;
    private final boolean timeoutBudgetEnabled;
    private final KafkaPropagationProperties kafka;
    private final @Nullable KafkaConsumerTimeLagMetrics timeLagMetrics;
    private final @Nullable MeterRegistry registry;

    public PulseKafkaRecordInterceptor(
            ContextProperties context,
            RetryProperties retry,
            PriorityProperties priority,
            TimeoutBudgetProperties timeoutBudget) {
        this(context, retry, priority, timeoutBudget, null, KafkaPropagationProperties.defaults(), null);
    }

    public PulseKafkaRecordInterceptor(
            ContextProperties context,
            RetryProperties retry,
            PriorityProperties priority,
            TimeoutBudgetProperties timeoutBudget,
            @Nullable KafkaConsumerTimeLagMetrics timeLagMetrics) {
        this(context, retry, priority, timeoutBudget, timeLagMetrics, KafkaPropagationProperties.defaults(), null);
    }

    public PulseKafkaRecordInterceptor(
            ContextProperties context,
            RetryProperties retry,
            PriorityProperties priority,
            TimeoutBudgetProperties timeoutBudget,
            @Nullable KafkaConsumerTimeLagMetrics timeLagMetrics,
            KafkaPropagationProperties kafka,
            @Nullable MeterRegistry registry) {
        this.headerToMdcKey = HeaderPropagation.headerToMdcKey(context, retry, priority);
        this.timeoutBudgetHeader = timeoutBudget.outboundHeader();
        this.timeoutBudgetEnabled = timeoutBudget.enabled();
        this.timeLagMetrics = timeLagMetrics;
        this.kafka = kafka;
        this.registry = registry;
    }

    @Override
    public @Nullable ConsumerRecord<Object, Object> intercept(
            ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        try {
            if (timeLagMetrics != null) {
                String groupId;
                try {
                    groupId = consumer.groupMetadata().groupId();
                } catch (RuntimeException ignored) {
                    groupId = KafkaConsumerTimeLagMetrics.UNKNOWN_GROUP;
                }
                timeLagMetrics.observe(record, groupId);
            }

            if (shouldSkipStale(record)) {
                recordStaleSkip(record);
                return null;
            }

            Set<String> putKeys = MDC_KEYS_PUT.get();
            headerToMdcKey.forEach((header, mdcKey) -> {
                Header h = record.headers().lastHeader(header);
                if (h != null && h.value() != null && MDC.get(mdcKey) == null) {
                    MDC.put(mdcKey, new String(h.value(), StandardCharsets.UTF_8));
                    putKeys.add(mdcKey);
                }
            });

            if (timeoutBudgetEnabled) {
                activateBudgetScope(record);
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

    private boolean shouldSkipStale(ConsumerRecord<?, ?> record) {
        if (!kafka.skipStaleRecords()) {
            return false;
        }
        Duration maxAge = kafka.skipStaleMaxAge();
        if (maxAge.isZero() || maxAge.isNegative()) {
            return false;
        }
        long timestamp = record.timestamp();
        if (timestamp <= 0L) {
            return false;
        }
        return System.currentTimeMillis() - timestamp > maxAge.toMillis();
    }

    private void recordStaleSkip(ConsumerRecord<?, ?> record) {
        if (registry == null) {
            return;
        }
        Counter.builder(STALE_SKIPPED_COUNTER)
                .description("Kafka records skipped because record age exceeded pulse.kafka.skip-stale-max-age")
                .tag("topic", record.topic())
                .register(registry)
                .increment();
    }

    @SuppressWarnings("MustBeClosedChecker") // Scope is closed in afterRecord — owned across hooks.
    private void activateBudgetScope(ConsumerRecord<?, ?> record) {
        Header deadlineHeader = record.headers().lastHeader(TimeoutBudget.KAFKA_DEADLINE_HEADER);
        if (deadlineHeader != null && deadlineHeader.value() != null) {
            String raw = new String(deadlineHeader.value(), StandardCharsets.UTF_8);
            Optional<TimeoutBudget> parsed = TimeoutBudget.parse(raw);
            if (parsed.isPresent()) {
                openBudgetScope(parsed.get());
                return;
            }
            log.debug("Pulse Kafka: malformed timeout-deadline header value '{}'", LogSanitizer.safe(raw));
        }
        Header remainingHeader = record.headers().lastHeader(timeoutBudgetHeader);
        if (remainingHeader == null || remainingHeader.value() == null) {
            return;
        }
        String headerValue = new String(remainingHeader.value(), StandardCharsets.UTF_8);
        try {
            long remainingMs = Long.parseLong(headerValue.trim());
            if (remainingMs < 0) {
                return;
            }
            // Legacy records: remaining duration from now. Prefer KAFKA_DEADLINE_HEADER on new produces.
            openBudgetScope(TimeoutBudget.withRemaining(Duration.ofMillis(remainingMs)));
        } catch (NumberFormatException e) {
            log.debug("Pulse Kafka: malformed timeout-budget header value '{}'", LogSanitizer.safe(headerValue));
        }
    }

    @SuppressWarnings("MustBeClosedChecker")
    private void openBudgetScope(TimeoutBudget budget) {
        Scope scope = Baggage.current().toBuilder()
                .put(TimeoutBudget.BAGGAGE_KEY, budget.toBaggageValue())
                .build()
                .makeCurrent();
        BAGGAGE_SCOPE.set(scope);
    }
}
