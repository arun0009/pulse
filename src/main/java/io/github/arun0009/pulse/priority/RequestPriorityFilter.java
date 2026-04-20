package io.github.arun0009.pulse.priority;

import io.github.arun0009.pulse.core.ContextKeys;
import io.github.arun0009.pulse.core.PulseRequestContextFilter;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves the request priority from the configured inbound header (default
 * {@code Pulse-Priority}), normalizes it, and writes it to {@link MDC} under
 * {@link ContextKeys#PRIORITY} and to the {@link RequestPriority} thread-local. Outbound
 * propagation is wired by {@link io.github.arun0009.pulse.propagation.HeaderPropagation} —
 * setting MDC is the only step needed for the priority to flow downstream.
 *
 * <p>Runs after {@link PulseRequestContextFilter} so the canonical request-context keys are
 * populated first, but before tenant resolution so per-tenant SLOs can be cross-cut by
 * priority later in the pipeline.
 */
public final class RequestPriorityFilter extends OncePerRequestFilter implements Ordered {

    /**
     * Slotted after TimeoutBudget (+10) but before Tenant (+30) and Retry (+40) so the
     * priority MDC key is available to whatever SPI extracts the tenant — a frequent
     * request for tenants-of-class-HIGH routing rules.
     */
    public static final int ORDER = PulseRequestContextFilter.ORDER + 20;

    private final String headerName;
    private final RequestPriority defaultPriority;

    public RequestPriorityFilter(PriorityProperties config) {
        this.headerName = config.headerName();
        this.defaultPriority = RequestPriority.parseOrDefault(config.defaultPriority(), RequestPriority.NORMAL);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        @Nullable String previousMdc = MDC.get(ContextKeys.PRIORITY);
        @Nullable RequestPriority previousContext = RequestPriority.current().orElse(null);
        RequestPriority resolved = RequestPriority.parseOrDefault(request.getHeader(headerName), defaultPriority);
        Baggage updated = Baggage.current().toBuilder()
                .put(ContextKeys.PRIORITY_BAGGAGE_KEY, resolved.wireValue())
                .build();
        try (Scope ignored = updated.storeInContext(Context.current()).makeCurrent()) {
            MDC.put(ContextKeys.PRIORITY, resolved.wireValue());
            RequestPriority.set(resolved);
            chain.doFilter(request, response);
        } finally {
            if (previousMdc == null) {
                MDC.remove(ContextKeys.PRIORITY);
            } else {
                MDC.put(ContextKeys.PRIORITY, previousMdc);
            }
            RequestPriority.set(previousContext);
        }
    }
}
