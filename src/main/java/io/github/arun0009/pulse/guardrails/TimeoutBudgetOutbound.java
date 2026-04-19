package io.github.arun0009.pulse.guardrails;

import io.github.arun0009.pulse.priority.RequestPriority;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * Helper consumed by every Pulse outbound interceptor (RestTemplate, RestClient, WebClient, OkHttp,
 * Kafka producer) so the {@code pulse.timeout_budget.exhausted} counter is incremented in exactly
 * the same way regardless of transport.
 *
 * <p>The counter is registered lazily and tagged with {@code transport} so dashboards can show
 * which client surface is most often racing the upstream deadline. When a request marked
 * {@link RequestPriority#CRITICAL} blows its budget, an additional {@code ERROR}-level log line
 * fires to surface the operationally important loss; non-critical exhaustion stays at
 * {@code DEBUG} to avoid noise on background traffic.
 */
public final class TimeoutBudgetOutbound {

    public static final String EXHAUSTED_COUNTER = "pulse.timeout_budget.exhausted";

    private static final Logger log = LoggerFactory.getLogger(TimeoutBudgetOutbound.class);

    private final @Nullable MeterRegistry registry;

    public TimeoutBudgetOutbound(@Nullable MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Returns the remaining budget if any; increments the exhaustion counter when the remaining
     * budget is zero (the upstream caller's deadline has already passed).
     */
    public Optional<Duration> resolveRemaining(String transport) {
        Optional<TimeoutBudget> current = TimeoutBudget.current();
        if (current.isEmpty()) return Optional.empty();
        Duration remaining = current.get().remaining();
        if (remaining.isZero()) {
            recordExhaustion(transport);
        }
        return Optional.of(remaining);
    }

    private void recordExhaustion(String transport) {
        if (registry != null) {
            Counter.builder(EXHAUSTED_COUNTER)
                    .description("Outbound calls made with zero remaining budget — the upstream caller's "
                            + "deadline was already past when this hop fired.")
                    .baseUnit("calls")
                    .tag("transport", transport)
                    .register(registry)
                    .increment();
        }
        if (RequestPriority.current().filter(RequestPriority::isCritical).isPresent()) {
            log.error(
                    "Pulse timeout-budget exhausted for a CRITICAL request on transport={}; downstream call will likely fail",
                    transport);
        }
    }
}
