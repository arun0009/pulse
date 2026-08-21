package io.github.arun0009.pulse.guardrails;

import java.time.Duration;

/**
 * Thrown when an outbound call is skipped because the remaining timeout budget is below
 * {@link TimeoutBudgetProperties#minimumBudget()} and
 * {@link TimeoutBudgetProperties#abortOnExhaustion()} is {@code true}.
 *
 * <p>Default is <em>not</em> to throw — Pulse stamps the remaining budget on the outbound
 * header and records {@code pulse.timeout_budget.exhausted} so you can see doomed calls
 * without changing request outcomes. Enable abort once dashboards confirm the 2s default
 * budget (or your own) matches the service's real latency envelope.
 */
public final class TimeoutBudgetExhaustedException extends RuntimeException {

    private final String transport;
    private final Duration remaining;

    public TimeoutBudgetExhaustedException(String transport, Duration remaining) {
        super("Pulse timeout-budget exhausted on transport=" + transport + " remaining=" + remaining
                + "; outbound call aborted");
        this.transport = transport;
        this.remaining = remaining;
    }

    public String transport() {
        return transport;
    }

    public Duration remaining() {
        return remaining;
    }
}
