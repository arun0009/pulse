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
 *   <li>{@link TimeoutBudget#DEADLINE_HEADER} (absolute epoch-millis) — preferred. Reconstructs
 *       the same deadline the caller had; does not restart remaining-ms from this hop.
 *   <li>Configured remaining-ms header (default {@code Pulse-Timeout-Ms}). {@code 0} is expired,
 *       not "no header". Tiny values are not floored up to {@code minimum-budget}.
 *   <li>Configured default budget, minus safety margin, floored at {@code minimum-budget}.
 * </ol>
 *
 * <p>{@link TimeoutBudgetProperties#safetyMargin()} applies when a remaining-ms header or the
 * default establishes a new deadline. An absolute deadline is not taxed again — the origin hop
 * already applied the margin. Values above {@link TimeoutBudgetProperties#maximumBudget()} are
 * clamped.
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

        Optional<TimeoutBudget> budget = resolveBudget(request);
        if (budget.isEmpty()) {
            // No inbound header and no positive default — leave baggage untouched so callers
            // observing TimeoutBudget.current() see Optional.empty() (no implicit safety net).
            chain.doFilter(request, response);
            return;
        }

        Baggage updated = Baggage.current().toBuilder()
                .put(TimeoutBudget.BAGGAGE_KEY, budget.get().toBaggageValue())
                .build();

        try (Scope ignored = updated.storeInContext(Context.current()).makeCurrent()) {
            chain.doFilter(request, response);
        }
    }

    private Optional<TimeoutBudget> resolveBudget(HttpServletRequest request) {
        Optional<TimeoutBudget> fromDeadline = TimeoutBudget.parse(request.getHeader(TimeoutBudget.DEADLINE_HEADER));
        if (fromDeadline.isPresent()) {
            return Optional.of(clampToMaximum(fromDeadline.get()));
        }

        String remainingHeader = request.getHeader(config.inboundHeader());
        if (remainingHeader != null && !remainingHeader.isBlank()) {
            Optional<Long> ms = parseMillis(remainingHeader);
            if (ms.isPresent()) {
                return Optional.of(fromExplicitRemaining(ms.get()));
            }
        }

        return fromDefaultBudget();
    }

    /**
     * An explicit remaining-ms header, including {@code 0}. Never falls back to the default and
     * never floors up to {@code minimum-budget} — that would mint time the caller no longer has.
     */
    private TimeoutBudget fromExplicitRemaining(long remainingMs) {
        if (remainingMs <= 0) {
            return TimeoutBudget.withRemaining(Duration.ZERO);
        }
        Duration raw = Duration.ofMillis(remainingMs);
        Duration maximum = config.maximumBudget();
        if (maximum != null && maximum.isPositive() && raw.compareTo(maximum) > 0) {
            log.debug("Pulse timeout-budget above maximum ({} > {}), clamping", raw, maximum);
            raw = maximum;
        }
        Duration adjusted = raw.minus(config.safetyMargin());
        if (adjusted.isNegative() || adjusted.isZero()) {
            return TimeoutBudget.withRemaining(Duration.ZERO);
        }
        return TimeoutBudget.withRemaining(adjusted);
    }

    private Optional<TimeoutBudget> fromDefaultBudget() {
        Duration raw = config.defaultBudget();
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
            return Optional.of(TimeoutBudget.withRemaining(config.minimumBudget()));
        }
        return Optional.of(TimeoutBudget.withRemaining(adjusted));
    }

    private TimeoutBudget clampToMaximum(TimeoutBudget budget) {
        Duration maximum = config.maximumBudget();
        if (maximum != null && maximum.isPositive() && budget.remaining().compareTo(maximum) > 0) {
            log.debug("Pulse timeout-budget above maximum ({} > {}), clamping", budget.remaining(), maximum);
            return TimeoutBudget.withRemaining(maximum);
        }
        return budget;
    }

    private static Optional<Long> parseMillis(String value) {
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
