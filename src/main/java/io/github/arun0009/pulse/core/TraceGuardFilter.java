package io.github.arun0009.pulse.core;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Detects inbound requests that arrived without a {@code traceparent} or {@code X-B3-TraceId}
 * header — a signal that an upstream caller is dropping trace context.
 *
 * <p>Records the metric {@code pulse.trace.missing} (low cardinality: tagged only by request path)
 * and either logs a WARN or fails fast based on {@link PulseProperties.TraceGuard#failOnMissing()}.
 */
public class TraceGuardFilter extends OncePerRequestFilter implements Ordered {

    public static final int ORDER = PulseRequestContextFilter.ORDER + 1;

    private static final Logger log = LoggerFactory.getLogger(TraceGuardFilter.class);
    private static final String TRACEPARENT = "traceparent";
    private static final String B3_TRACE_ID = "X-B3-TraceId";

    private final MeterRegistry registry;
    private final PulseProperties.TraceGuard config;

    public TraceGuardFilter(MeterRegistry registry, PulseProperties.TraceGuard config) {
        this.registry = registry;
        this.config = config;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (isExempt(request.getRequestURI(), config.excludePathPrefixes())) {
            chain.doFilter(request, response);
            return;
        }

        boolean hasTrace = request.getHeader(TRACEPARENT) != null || request.getHeader(B3_TRACE_ID) != null;

        if (!hasTrace) {
            registry.counter("pulse.trace.missing", "path", request.getRequestURI())
                    .increment();
            if (config.failOnMissing()) {
                throw new ServletException("Pulse TraceGuard: incoming request is missing trace-context headers ("
                        + TRACEPARENT
                        + " / "
                        + B3_TRACE_ID
                        + "). Configure your upstream caller to propagate context, or set "
                        + "pulse.trace-guard.fail-on-missing=false.");
            }
            log.warn("Pulse TraceGuard: missing trace context for {}", request.getRequestURI());
        }

        chain.doFilter(request, response);
    }

    private static boolean isExempt(String path, List<String> exemptPrefixes) {
        if (path == null || exemptPrefixes == null) return false;
        for (String prefix : exemptPrefixes) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }
}
