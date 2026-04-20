package io.github.arun0009.pulse.guardrails;

import io.github.arun0009.pulse.core.PulseRequestContextFilter;
import io.github.arun0009.pulse.core.PulseRequestMatcher;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Inbound filter that establishes the request's timeout budget and stores its absolute deadline on
 * OTel {@link Baggage} for downstream propagation.
 *
 * <p>Resolution order:
 *
 * <ol>
 *   <li>If the inbound request carries the configured header (default {@code Pulse-Timeout-Ms}), parse
 *       it.
 *   <li>Otherwise, fall back to the configured default budget.
 * </ol>
 *
 * <p>Floors the budget at {@link TimeoutBudgetProperties#minimumBudget()} and applies {@link
 * TimeoutBudgetProperties#safetyMargin()} so the inbound work has a small buffer before the
 * upstream caller cancels. Inbound values above {@link TimeoutBudgetProperties#maximumBudget()}
 * are clamped before the margin is applied.
 */
public class TimeoutBudgetFilter extends OncePerRequestFilter implements Ordered {

    /**
     * Anchored to {@link PulseRequestContextFilter#ORDER} so the MDC keys and request id are
     * populated before TimeoutBudget emits any WARN. Running before RequestContext would strip
     * the request/trace ids from every deadline-breach log line, defeating the "every log
     * correlates" guarantee Pulse sells.
     */
    public static final int ORDER = PulseRequestContextFilter.ORDER + 10;

    private static final Logger log = LoggerFactory.getLogger(TimeoutBudgetFilter.class);

    private final TimeoutBudgetProperties config;
    private final PulseRequestMatcher gate;

    public TimeoutBudgetFilter(TimeoutBudgetProperties config) {
        this(config, PulseRequestMatcher.ALWAYS);
    }

    public TimeoutBudgetFilter(TimeoutBudgetProperties config, PulseRequestMatcher gate) {
        this.config = config;
        this.gate = gate;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!gate.matches(request)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<Duration> budget = resolveBudget(request);
        if (budget.isEmpty()) {
            // No inbound header and no positive default — leave baggage untouched so callers
            // observing TimeoutBudget.current() see Optional.empty() (no implicit safety net).
            chain.doFilter(request, response);
            return;
        }

        TimeoutBudget current = TimeoutBudget.withRemaining(budget.get());
        Baggage updated = Baggage.current().toBuilder()
                .put(TimeoutBudget.BAGGAGE_KEY, current.toBaggageValue())
                .build();

        try (Scope ignored = updated.storeInContext(Context.current()).makeCurrent()) {
            chain.doFilter(request, response);
        }
    }

    private Optional<Duration> resolveBudget(HttpServletRequest request) {
        Optional<Duration> inbound = parseHeader(request.getHeader(config.inboundHeader()));
        Duration raw = inbound.orElse(config.defaultBudget());
        if (raw == null || raw.isZero() || raw.isNegative()) {
            return Optional.empty();
        }
        Duration maximum = config.maximumBudget();
        if (maximum != null && maximum.isPositive() && raw.compareTo(maximum) > 0) {
            log.debug("Pulse timeout-budget above maximum ({} > {}), clamping", raw, maximum);
            raw = maximum;
        }
        Duration adjusted = raw.minus(config.safetyMargin());
        if (adjusted.compareTo(config.minimumBudget()) < 0) {
            log.debug("Pulse timeout-budget below minimum ({} < {}), flooring", adjusted, config.minimumBudget());
            return Optional.of(config.minimumBudget());
        }
        return Optional.of(adjusted);
    }

    private static Optional<Duration> parseHeader(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            long ms = Long.parseLong(value.trim());
            return ms > 0 ? Optional.of(Duration.ofMillis(ms)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
