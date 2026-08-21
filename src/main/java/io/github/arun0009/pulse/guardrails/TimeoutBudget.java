package io.github.arun0009.pulse.guardrails;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Represents the remaining time budget for an in-flight request.
 *
 * <p>Pulse extracts an inbound {@code Pulse-Timeout-Ms} header (or whatever
 * {@code pulse.timeout-budget.inbound-header} resolves to), anchors a deadline to the request
 * start, and stores the deadline (as epoch-millis) on the OTel {@link Baggage} so it propagates
 * across every async hop and downstream call the OTel SDK touches. Application code reads the
 * remaining budget via {@link #current()} and downstream interceptors stamp that remaining
 * budget on {@code Pulse-Timeout-Ms} plus the absolute deadline on {@link #DEADLINE_HEADER}.
 * Set {@code pulse.timeout-budget.abort-on-exhaustion=true} to skip the outbound HTTP call when
 * the remaining budget is below {@code minimum-budget}. Set
 * {@code pulse.timeout-budget.apply-client-timeout=true} to bound OkHttp / WebClient / Apache
 * HttpClient 5 to the remaining budget. RestTemplate and RestClient have no per-request timeout
 * API — use abort-on-exhaustion there.
 *
 * <p>Why this matters: without budget propagation, one slow downstream eats the caller's entire
 * remaining time. Each successive hop falls back to its platform default (often 30s on the first
 * try, then retries). A 2-second inbound SLA blows up into a 30-second cascading hang. With Pulse,
 * every hop reconstructs the <em>same</em> absolute deadline. Enable abort-on-exhaustion to fail
 * fast instead of issuing a doomed call, and apply-client-timeout so a live call cannot outlive
 * the caller.
 */
public final class TimeoutBudget {

    /** Baggage key used to carry the absolute deadline (epoch-millis, decimal string). */
    public static final String BAGGAGE_KEY = "pulse.timeout-budget.deadline.epoch.ms";

    /**
     * Absolute epoch-millis deadline on HTTP and Kafka. Distinct from {@code Pulse-Timeout-Ms}
     * (remaining duration): a remaining-ms value treated as "from now" on the next hop restarts
     * the clock after Kafka queueing or a slow HTTP proxy. Kafka record timestamps are not used
     * (they are often event-time, not produce wall-clock).
     */
    public static final String DEADLINE_HEADER = "Pulse-Timeout-Deadline-Ms";

    /** Same wire header as {@link #DEADLINE_HEADER}. */
    public static final String KAFKA_DEADLINE_HEADER = DEADLINE_HEADER;

    private final Instant deadline;

    private TimeoutBudget(Instant deadline) {
        this.deadline = deadline;
    }

    public static TimeoutBudget withRemaining(Duration remaining) {
        return new TimeoutBudget(Instant.now().plus(remaining));
    }

    public static TimeoutBudget atDeadline(Instant deadline) {
        return new TimeoutBudget(deadline);
    }

    public Instant deadline() {
        return deadline;
    }

    public Duration remaining() {
        Duration remaining = Duration.between(Instant.now(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public boolean expired() {
        return !Instant.now().isBefore(deadline);
    }

    /** Returns the deadline-millis encoded for transmission on baggage / headers. */
    public String toBaggageValue() {
        return Long.toString(deadline.toEpochMilli());
    }

    /**
     * Reads the current request's budget from OTel baggage, if Pulse's {@code TimeoutBudgetFilter}
     * ran upstream.
     */
    public static Optional<TimeoutBudget> current() {
        BaggageEntry entry = Baggage.current().getEntry(BAGGAGE_KEY);
        if (entry == null) {
            return Optional.empty();
        }
        return parse(entry.getValue());
    }

    /** Parses a deadline-epoch-millis baggage value, returning empty if malformed. */
    public static Optional<TimeoutBudget> parse(String baggageValue) {
        if (baggageValue == null || baggageValue.isBlank()) return Optional.empty();
        try {
            return Optional.of(new TimeoutBudget(Instant.ofEpochMilli(Long.parseLong(baggageValue.trim()))));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
