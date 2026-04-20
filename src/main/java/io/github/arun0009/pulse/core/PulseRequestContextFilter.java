package io.github.arun0009.pulse.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Populates the SLF4J/Log4j2 MDC with Pulse-canonical keys on every request and mirrors the request
 * id back to the response so callers can quote it in support tickets.
 *
 * <p>Runs near the top of the filter chain so that downstream logging and the trace-id / span-id
 * added by the OTel log appender end up on the same record.
 *
 * <p><strong>MDC lifecycle</strong>: this filter follows a strict <em>save-on-entry, restore-on-exit</em>
 * discipline. Any keys the surrounding caller had set on MDC (test harnesses, executor decorators,
 * outer filters) are preserved and restored when the request leaves Pulse's scope. Pulse-managed
 * keys are removed in the finally block. Calling {@link MDC#clear()} is intentionally avoided —
 * it would wipe out keys Pulse does not own.
 */
public class PulseRequestContextFilter extends OncePerRequestFilter implements Ordered {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    private final String serviceName;
    private final String environment;
    private final ContextProperties contextConfig;
    private final List<ContextContributor> contributors;

    public PulseRequestContextFilter(
            String serviceName,
            String environment,
            ContextProperties contextConfig,
            List<ContextContributor> contributors) {
        this.serviceName = serviceName;
        this.environment = environment;
        this.contextConfig = contextConfig;
        this.contributors = contributors == null ? List.of() : contributors;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        Set<String> keysAdded = new LinkedHashSet<>();
        try {
            putAndTrack(ContextKeys.SERVICE_NAME, serviceName, keysAdded);
            putAndTrack(ContextKeys.ENVIRONMENT, environment, keysAdded);

            String requestId = headerOrGenerate(request, contextConfig.requestIdHeader());
            putAndTrack(ContextKeys.REQUEST_ID, requestId, keysAdded);
            response.setHeader(contextConfig.requestIdHeader(), requestId);

            putHeaderIfPresent(request, contextConfig.correlationIdHeader(), ContextKeys.CORRELATION_ID, keysAdded);
            putHeaderIfPresent(request, contextConfig.userIdHeader(), ContextKeys.USER_ID, keysAdded);
            putHeaderIfPresent(request, contextConfig.tenantIdHeader(), ContextKeys.TENANT_ID, keysAdded);
            putHeaderIfPresent(request, contextConfig.idempotencyKeyHeader(), ContextKeys.IDEMPOTENCY_KEY, keysAdded);

            for (String header : contextConfig.additionalHeaders()) {
                putHeaderIfPresent(request, header, header, keysAdded);
            }

            for (ContextContributor contributor : contributors) {
                contributor.contribute(request);
            }

            chain.doFilter(request, response);

            String traceId = MDC.get(ContextKeys.TRACE_ID);
            if (traceId != null && !response.isCommitted()) {
                response.setHeader(ContextKeys.RESPONSE_TRACE_HEADER, traceId);
            }
        } finally {
            // Restore exactly the snapshot taken on entry. Keys Pulse added that weren't already
            // present are removed; keys whose prior value we displaced are restored.
            for (String key : keysAdded) {
                String prior = previousMdc == null ? null : previousMdc.get(key);
                if (prior == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, prior);
                }
            }
        }
    }

    private static void putAndTrack(String key, String value, Set<String> tracker) {
        MDC.put(key, value);
        tracker.add(key);
    }

    private static void putHeaderIfPresent(
            HttpServletRequest request, String headerName, String mdcKey, Set<String> tracker) {
        String value = request.getHeader(headerName);
        if (value != null && !value.isBlank()) {
            putAndTrack(mdcKey, value, tracker);
        }
    }

    private static String headerOrGenerate(HttpServletRequest request, String headerName) {
        String value = request.getHeader(headerName);
        return (value == null || value.isBlank()) ? UUID.randomUUID().toString() : value;
    }
}
