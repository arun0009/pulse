package io.github.arun0009.pulse.guardrails;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Optional;

/**
 * Helper consumed by every Pulse outbound interceptor (RestTemplate, RestClient, WebClient, OkHttp,
 * Kafka producer) so the {@code pulse.timeout.budget.exhausted} counter is incremented in exactly
 * the same way regardless of transport.
 *
 * <p>The counter is registered lazily and tagged with {@code transport} so dashboards can show
 * which client surface is most often racing the upstream deadline.
 */
public final class TimeoutBudgetOutbound {

    public static final String EXHAUSTED_COUNTER = "pulse.timeout.budget.exhausted";

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
        if (remaining.isZero() && registry != null) {
            Counter.builder(EXHAUSTED_COUNTER)
                    .description("Outbound calls made with zero remaining budget — the upstream caller's "
                            + "deadline was already past when this hop fired.")
                    .baseUnit("calls")
                    .tag("transport", transport)
                    .register(registry)
                    .increment();
        }
        return Optional.of(remaining);
    }
}
