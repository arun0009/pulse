package io.github.arun0009.pulse.resilience;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.github.arun0009.pulse.core.ContextKeys;
import io.github.arun0009.pulse.core.PulseRequestContextFilter;
import io.github.arun0009.pulse.core.RouteTags;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Inbound filter that seeds {@link RetryDepthContext} from the upstream's
 * {@code Pulse-Retry-Depth} header (or whatever {@code pulse.retry.header-name} resolves to)
 * and emits the amplification metric/event/log when the depth crosses the configured threshold.
 *
 * <p>Runs after {@link PulseRequestContextFilter} (so MDC is already populated with the standard
 * request id / trace id) and before any application code, ensuring the depth is visible on every
 * log line and metric emitted within the request scope.
 *
 * <p>The filter does not increment the depth itself — that is {@link RetryObservation}'s job
 * when a Resilience4j retry actually fires. The filter only seeds the inbound value and tears
 * the thread-local down on exit so the next request handled by the same thread starts clean.
 */
public final class RetryDepthFilter extends OncePerRequestFilter implements Ordered {

    public static final String MDC_KEY = ContextKeys.RETRY_DEPTH;
    public static final int ORDER = PulseRequestContextFilter.ORDER + 20;

    private static final Logger log = LoggerFactory.getLogger(RetryDepthFilter.class);

    private final String headerName;
    private final int amplificationThreshold;
    private final MeterRegistry registry;

    public RetryDepthFilter(PulseProperties.Retry config, MeterRegistry registry) {
        this.headerName = config.headerName();
        this.amplificationThreshold = config.amplificationThreshold();
        this.registry = registry;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        @Nullable String previousMdc = MDC.get(MDC_KEY);
        int previousContext = RetryDepthContext.current();
        int inbound = parse(request.getHeader(headerName));
        boolean amplified = inbound >= amplificationThreshold;
        try {
            if (inbound > 0) {
                RetryDepthContext.set(inbound);
                MDC.put(MDC_KEY, Integer.toString(inbound));
            }
            if (amplified) {
                Span span = Span.current();
                SpanContext context = span.getSpanContext();
                if (context.isValid()) {
                    span.addEvent("pulse.retry.amplification");
                    span.setAttribute("pulse.retry.depth", inbound);
                }
            }
            chain.doFilter(request, response);
        } finally {
            if (amplified) {
                String route = RouteTags.of(request);
                Counter.builder("pulse.retry.amplification")
                        .tag("endpoint", route)
                        .description("Inbound requests whose retry-depth crossed the amplification threshold")
                        .register(registry)
                        .increment();
                log.warn(
                        "Pulse retry-amplification detected: depth={} route={} threshold={}",
                        inbound,
                        route,
                        amplificationThreshold);
            }
            if (previousMdc == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousMdc);
            }
            if (previousContext == 0) {
                RetryDepthContext.clear();
            } else {
                RetryDepthContext.set(previousContext);
            }
        }
    }

    private static int parse(@Nullable String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(parsed, 0);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
