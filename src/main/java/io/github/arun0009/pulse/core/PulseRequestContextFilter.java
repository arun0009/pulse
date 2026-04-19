package io.github.arun0009.pulse.core;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Populates the SLF4J/Log4j2 MDC with Pulse-canonical keys on every request and mirrors the request
 * id back to the response so callers can quote it in support tickets.
 *
 * <p>Runs near the top of the filter chain so that downstream logging and the trace-id / span-id
 * added by the OTel log appender end up on the same record.
 */
public class PulseRequestContextFilter extends OncePerRequestFilter implements Ordered {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    private final String serviceName;
    private final String environment;
    private final PulseProperties.Context contextConfig;
    private final List<ContextContributor> contributors;

    public PulseRequestContextFilter(
            String serviceName,
            String environment,
            PulseProperties.Context contextConfig,
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

        try {
            MDC.put(ContextKeys.SERVICE_NAME, serviceName);
            MDC.put(ContextKeys.ENVIRONMENT, environment);

            String requestId = headerOrGenerate(request, contextConfig.requestIdHeader());
            MDC.put(ContextKeys.REQUEST_ID, requestId);
            response.setHeader(contextConfig.requestIdHeader(), requestId);

            putHeaderIfPresent(request, contextConfig.correlationIdHeader(), ContextKeys.CORRELATION_ID);
            putHeaderIfPresent(request, contextConfig.userIdHeader(), ContextKeys.USER_ID);
            putHeaderIfPresent(request, contextConfig.tenantIdHeader(), ContextKeys.TENANT_ID);
            putHeaderIfPresent(request, contextConfig.idempotencyKeyHeader(), ContextKeys.IDEMPOTENCY_KEY);

            for (String header : contextConfig.additionalHeaders()) {
                putHeaderIfPresent(request, header, header);
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
            MDC.clear();
        }
    }

    private static String headerOrGenerate(HttpServletRequest request, String headerName) {
        String value = request.getHeader(headerName);
        return (value == null || value.isBlank()) ? UUID.randomUUID().toString() : value;
    }

    private static void putHeaderIfPresent(HttpServletRequest request, String headerName, String mdcKey) {
        String value = request.getHeader(headerName);
        if (value != null && !value.isBlank()) {
            MDC.put(mdcKey, value);
        }
    }
}
