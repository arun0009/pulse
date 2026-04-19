package io.github.arun0009.pulse.propagation;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Static holder that bridges Spring-managed Pulse configuration into the Kafka native
 * {@link org.apache.kafka.clients.producer.ProducerInterceptor} which Kafka instantiates itself
 * with a no-arg constructor (so it cannot be Spring-injected directly).
 *
 * <p>Initialized exactly once during application startup by {@link KafkaPropagationConfiguration}
 * and read from the producer thread on every {@code send()}.
 */
public final class KafkaPropagationContext {

    private static volatile Map<String, String> headerToMdcKey = Map.of();
    private static volatile String timeoutBudgetHeader = "X-Timeout-Ms";
    private static volatile boolean initialized = false;

    private KafkaPropagationContext() {}

    static synchronized void initialize(Map<String, String> headerToMdcKey, String timeoutBudgetHeader) {
        KafkaPropagationContext.headerToMdcKey = Map.copyOf(headerToMdcKey);
        KafkaPropagationContext.timeoutBudgetHeader = timeoutBudgetHeader;
        KafkaPropagationContext.initialized = true;
    }

    public static Map<String, String> headerToMdcKey() {
        return headerToMdcKey;
    }

    public static String timeoutBudgetHeader() {
        return timeoutBudgetHeader;
    }

    public static boolean initialized() {
        return initialized;
    }

    /** Test-only — restore default state. */
    static synchronized void resetForTesting(@Nullable Map<String, String> headers, @Nullable String budgetHeader) {
        headerToMdcKey = headers == null ? Map.of() : Map.copyOf(headers);
        timeoutBudgetHeader = budgetHeader == null ? "X-Timeout-Ms" : budgetHeader;
        initialized = headers != null;
    }
}
