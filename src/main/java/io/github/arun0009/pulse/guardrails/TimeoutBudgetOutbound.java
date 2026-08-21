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
 *
 * <p>Aborting the outbound call is <strong>opt-in</strong> via
 * {@link TimeoutBudgetProperties#abortOnExhaustion()}. The default is to stamp a remaining-budget
 * header (including {@code 0}) and still make the call. Aborting a brownfield service on the 2s
 * default budget would be a landing-page rejection. Bounding the client's own timeout is a
 * separate opt-in ({@link TimeoutBudgetProperties#applyClientTimeout()}) because a 2s default
 * applied as a socket timeout is equally aggressive. Kafka always stamps and never aborts —
 * dropping a produce is not an observability decision.
 */
public final class TimeoutBudgetOutbound {

    public static final String EXHAUSTED_COUNTER = "pulse.timeout_budget.exhausted";

    private static final Logger log = LoggerFactory.getLogger(TimeoutBudgetOutbound.class);

    private final @Nullable MeterRegistry registry;
    private final @Nullable TimeoutBudgetProperties config;

    public TimeoutBudgetOutbound(@Nullable MeterRegistry registry) {
        this(registry, null);
    }

    public TimeoutBudgetOutbound(@Nullable MeterRegistry registry, @Nullable TimeoutBudgetProperties config) {
        this.registry = registry;
        this.config = config;
    }

    /**
     * Remaining budget for an outbound hop that must not fail the call (Kafka produce). Still
     * increments the exhaustion counter when the remaining budget is at or below the floor.
     */
    public Optional<Duration> resolveRemaining(String transport) {
        return evaluate(transport, false);
    }

    /**
     * Remaining budget for an outbound HTTP hop. Increments the exhaustion counter when the
     * remaining budget is at or below {@code minimum-budget}. Throws
     * {@link TimeoutBudgetExhaustedException} when {@code abort-on-exhaustion} is {@code true}.
     */
    public Optional<Duration> remainingForOutbound(String transport) {
        return evaluate(transport, true);
    }

    /**
     * Whether outbound HTTP clients should cap their own timeout at the remaining budget.
     * RestTemplate / RestClient ignore this — they have no per-request timeout API.
     */
    public boolean applyClientTimeout() {
        return config != null && config.applyClientTimeout();
    }

    /**
     * Set a header only when it is not already present. Existing application headers win.
     */
    @FunctionalInterface
    public interface HeaderSink {
        void putIfAbsent(String name, String value);
    }

    /**
     * Stamps remaining-ms and the absolute deadline. HTTP callers pass {@code honorAbort=true};
     * Kafka must pass {@code false} so a produce is never dropped.
     */
    public void stampHeaders(String remainingHeader, String transport, boolean honorAbort, HeaderSink sink) {
        Optional<Duration> remaining = honorAbort ? remainingForOutbound(transport) : resolveRemaining(transport);
        remaining.ifPresent(r -> sink.putIfAbsent(remainingHeader, Long.toString(r.toMillis())));
        TimeoutBudget.current()
                .ifPresent(budget -> sink.putIfAbsent(TimeoutBudget.DEADLINE_HEADER, budget.toBaggageValue()));
    }

    /**
     * Remaining budget to apply as a client-side timeout. Empty when the flag is off, there is
     * no current budget, or remaining time is below 1 ms (a 0 ms OkHttp/HC5 timeout means
     * "infinite", which is the opposite of what we want).
     */
    public Optional<Duration> remainingForClientTimeout() {
        if (!applyClientTimeout()) {
            return Optional.empty();
        }
        return TimeoutBudget.current().map(TimeoutBudget::remaining).filter(d -> d.toMillis() > 0);
    }

    private Optional<Duration> evaluate(String transport, boolean honorAbort) {
        Optional<TimeoutBudget> current = TimeoutBudget.current();
        if (current.isEmpty()) return Optional.empty();
        Duration remaining = current.get().remaining();
        Duration floor = config != null ? config.minimumBudget() : Duration.ZERO;
        if (remaining.compareTo(floor) < 0 || remaining.isZero()) {
            recordExhaustion(transport);
            if (honorAbort && config != null && config.abortOnExhaustion()) {
                throw new TimeoutBudgetExhaustedException(transport, remaining);
            }
        }
        return Optional.of(remaining);
    }

    private void recordExhaustion(String transport) {
        if (registry != null) {
            Counter.builder(EXHAUSTED_COUNTER)
                    .description("Outbound calls made (or aborted) with remaining budget at or below"
                            + " pulse.timeout-budget.minimum-budget — the upstream caller's deadline"
                            + " was already past when this hop fired.")
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
