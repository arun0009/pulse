package io.github.arun0009.pulse.core;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
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
 * Detects inbound requests that arrived without an OpenTelemetry trace context — a signal that
 * an upstream caller is dropping or stripping {@code traceparent} headers. Presence (not full
 * W3C-syntax validity) is what we check: if the OTel context is missing and the raw header is
 * absent, the request counts as "missing". Format validation is left to OTel's own propagator.
 * Also exposes the
 * positive-case counter ({@code pulse.trace.received}) so that "trace propagation health" can be
 * computed as a single PromQL ratio.
 *
 * <p>Both counters are tagged by the Spring route template ({@code /users/{id}}) — never by the
 * raw request URI — so cardinality stays bounded even under id-bearing path segments. Requests
 * that do not match a route (404 paths, file uploads, etc.) bucket to a {@code other} tag.
 *
 * <p>Resolution order for "do we have trace context?":
 *
 * <ol>
 *   <li>Check the live OpenTelemetry {@link Span} — Spring Boot's OTel starter has already run
 *       its propagator at this point in the chain, so this is the source of truth.
 *   <li>If the OTel context is missing, fall back to the W3C {@code traceparent} header (and
 *       legacy B3 {@code X-B3-TraceId}) so we still detect the case where the OTel SDK is
 *       absent or disabled.
 * </ol>
 *
 * <p>Behavior on missing context is governed by {@link PulseProperties.TraceGuard#failOnMissing()}.
 *
 * <p>A request can be excluded from the guard in two ways:
 * <ol>
 *   <li>{@link PulseProperties.TraceGuard#excludePathPrefixes()} — coarse, path-prefix-based,
 *       evaluated first. Defaults cover {@code /actuator}, {@code /health}, {@code /metrics}.
 *   <li>{@link PulseProperties.TraceGuard#enabledWhen()} (since 1.1) — declarative
 *       header/path predicate compiled by {@code PulseRequestMatcherFactory}. Lets you skip the
 *       guard for synthetic monitoring traffic, smoke tests, or trusted internal callers without
 *       turning the feature off globally.
 * </ol>
 */
public class TraceGuardFilter extends OncePerRequestFilter implements Ordered {

    public static final int ORDER = PulseRequestContextFilter.ORDER + 1;

    private static final Logger log = LoggerFactory.getLogger(TraceGuardFilter.class);

    /** W3C Trace Context header name; mirrors {@code W3CTraceContextPropagator.TRACE_PARENT}. */
    private static final String TRACEPARENT = "traceparent";

    /** Legacy Zipkin/B3 header name; mirrors {@code B3Propagator}'s single-header constant. */
    private static final String B3_TRACE_ID = "X-B3-TraceId";

    private final MeterRegistry registry;
    private final PulseProperties.TraceGuard config;
    private final PulseRequestMatcher gate;

    public TraceGuardFilter(MeterRegistry registry, PulseProperties.TraceGuard config) {
        this(registry, config, PulseRequestMatcher.ALWAYS);
    }

    public TraceGuardFilter(MeterRegistry registry, PulseProperties.TraceGuard config, PulseRequestMatcher gate) {
        this.registry = registry;
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

        if (isExempt(request.getRequestURI(), config.excludePathPrefixes())) {
            chain.doFilter(request, response);
            return;
        }

        if (!gate.matches(request)) {
            chain.doFilter(request, response);
            return;
        }

        String routeTag = RouteTags.of(request);
        boolean hasTrace = hasTraceContext(request);

        if (hasTrace) {
            Counter.builder("pulse.trace.received")
                    .description("Inbound requests that arrived with a valid OTel/W3C trace context")
                    .tag("route", routeTag)
                    .register(registry)
                    .increment();
            chain.doFilter(request, response);
            return;
        }

        Counter.builder("pulse.trace.missing")
                .description("Inbound requests that arrived without a valid trace context — propagation gap upstream")
                .tag("route", routeTag)
                .register(registry)
                .increment();

        if (config.failOnMissing()) {
            throw new ServletException("Pulse TraceGuard: incoming request is missing trace-context headers ("
                    + TRACEPARENT
                    + " / "
                    + B3_TRACE_ID
                    + "). Configure your upstream caller to propagate context, or set "
                    + "pulse.trace-guard.fail-on-missing=false.");
        }
        log.warn("Pulse TraceGuard: missing trace context for route={}", routeTag);
        chain.doFilter(request, response);
    }

    private static boolean hasTraceContext(HttpServletRequest request) {
        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) return true;
        return request.getHeader(TRACEPARENT) != null || request.getHeader(B3_TRACE_ID) != null;
    }

    private static boolean isExempt(String path, List<String> exemptPrefixes) {
        if (path == null || exemptPrefixes == null) return false;
        for (String prefix : exemptPrefixes) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }
}
