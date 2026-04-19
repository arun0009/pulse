package io.github.arun0009.pulse.guardrails;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Represents the remaining time budget for an in-flight request.
 *
 * <p>Pulse extracts an inbound {@code X-Timeout-Ms} header, anchors a deadline to the request
 * start, and stores the deadline (as epoch-millis) on the OTel {@link Baggage} so it propagates
 * across every async hop and downstream call the OTel SDK touches. Application code reads the
 * remaining budget via {@link #current()} and downstream HTTP/gRPC interceptors translate that
 * remaining budget into per-call timeouts.
 *
 * <p>Why this matters: without budget propagation, one slow downstream eats the caller's entire
 * remaining time. Each successive hop falls back to its platform default (often 30s on the first
 * try, then retries). A 2-second inbound SLA blows up into a 30-second cascading hang. With Pulse,
 * every hop receives the <em>actual</em> remaining time and fails fast when there is not enough
 * left to be useful.
 */
public final class TimeoutBudget {

    /** Baggage key used to carry the absolute deadline (epoch-millis, decimal string). */
    public static final String BAGGAGE_KEY = "pulse.deadline.epoch.ms";

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
