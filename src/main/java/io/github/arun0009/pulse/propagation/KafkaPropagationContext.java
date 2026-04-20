package io.github.arun0009.pulse.propagation;

import io.github.arun0009.pulse.guardrails.TimeoutBudgetOutbound;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Static holder that bridges Spring-managed Pulse configuration into the Kafka native
 * {@link org.apache.kafka.clients.producer.ProducerInterceptor} which Kafka instantiates itself
 * with a no-arg constructor (so it cannot be Spring-injected directly).
 *
 * <p>Initialized exactly once during application startup by
 * {@link io.github.arun0009.pulse.propagation.internal.KafkaPropagationConfiguration}
 * and read from the producer thread on every {@code send()}.
 */
public final class KafkaPropagationContext {

    private static volatile Map<String, String> headerToMdcKey = Map.of();
    private static volatile String timeoutBudgetHeader = "Pulse-Timeout-Ms";
    private static volatile @Nullable TimeoutBudgetOutbound budgetHelper = null;
    private static volatile boolean initialized = false;

    private KafkaPropagationContext() {}

    /**
     * Internal — called once at application startup by Pulse's Kafka autoconfig to bridge
     * Spring-managed configuration into the Kafka-native ProducerInterceptor (which Kafka
     * instantiates itself with a no-arg constructor and so cannot be Spring-injected).
     * Applications should not call this directly.
     */
    public static synchronized void initialize(
            Map<String, String> headerToMdcKey, String timeoutBudgetHeader, @Nullable MeterRegistry meterRegistry) {
        KafkaPropagationContext.headerToMdcKey = Map.copyOf(headerToMdcKey);
        KafkaPropagationContext.timeoutBudgetHeader = timeoutBudgetHeader;
        KafkaPropagationContext.budgetHelper = new TimeoutBudgetOutbound(meterRegistry);
        KafkaPropagationContext.initialized = true;
    }

    public static Map<String, String> headerToMdcKey() {
        return headerToMdcKey;
    }

    public static String timeoutBudgetHeader() {
        return timeoutBudgetHeader;
    }

    public static @Nullable TimeoutBudgetOutbound budgetHelper() {
        return budgetHelper;
    }

    public static boolean initialized() {
        return initialized;
    }

    /** Test-only — restore default state. */
    static synchronized void resetForTesting(@Nullable Map<String, String> headers, @Nullable String budgetHeader) {
        headerToMdcKey = headers == null ? Map.of() : Map.copyOf(headers);
        timeoutBudgetHeader = budgetHeader == null ? "Pulse-Timeout-Ms" : budgetHeader;
        budgetHelper = new TimeoutBudgetOutbound(null);
        initialized = headers != null;
    }
}
