package io.github.arun0009.pulse.core;

import io.github.arun0009.pulse.enforcement.PulseEnforcementMode;
import io.github.arun0009.pulse.tracing.internal.PulseSpans;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
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
 *   <li>Check the live Micrometer {@link Tracer#currentSpan() current span} — Spring Boot's
 *       tracing starter (Micrometer Tracing + OTel/Brave bridge) has already run its propagator
 *       by this point in the chain, so this is the source of truth.
 *   <li>If the tracing context is missing, fall back to the W3C {@code traceparent} header
 *       (and legacy B3 {@code X-B3-TraceId}) so we still detect the case where no tracer is
 *       wired or tracing is disabled.
 * </ol>
 *
 * <p>Behavior on missing context is governed by {@link TraceGuardProperties#failOnMissing()}.
 *
 * <p>A request can be excluded from the guard in two ways:
 * <ol>
 *   <li>{@link TraceGuardProperties#excludePathPrefixes()} — coarse, path-prefix-based,
 *       evaluated first. Defaults cover {@code /actuator}, {@code /health}, {@code /metrics}.
 *   <li>{@link TraceGuardProperties#enabledWhen()} (since 1.1) — declarative
 *       header/path predicate compiled by {@code PulseRequestMatcherFactory}. Lets you skip the
 *       guard for synthetic monitoring traffic, smoke tests, or trusted internal callers without
 *       turning the feature off globally.
 * </ol>
 */
public class TraceGuardFilter extends OncePerRequestFilter implements Ordered {

    /**
     * Intentionally ordered <em>last</em> within the Pulse inbound cluster (after
     * TimeoutBudget / Priority / Tenant / RetryDepth). Every MDC field those earlier filters set
     * — tenant id, priority, retry depth, deadline — is therefore attached to the missing-trace
     * WARN that this filter emits, which is essential to triage: a trace-context leak that
     * correlates with {@code tenantId=acme} + {@code priority=HIGH} is a wholly different bug
     * from one that happens only on retry bursts.
     */
    public static final int ORDER = PulseRequestContextFilter.ORDER + 50;

    private static final Logger log = LoggerFactory.getLogger(TraceGuardFilter.class);

    /** W3C Trace Context header name; mirrors {@code W3CTraceContextPropagator.TRACE_PARENT}. */
    private static final String TRACEPARENT = "traceparent";

    /** Legacy Zipkin/B3 header name; mirrors {@code B3Propagator}'s single-header constant. */
    private static final String B3_TRACE_ID = "X-B3-TraceId";

    private final MeterRegistry registry;
    private final TraceGuardProperties config;
    private final PulseRequestMatcher gate;
    private final PulseEnforcementMode enforcement;
    private final Tracer tracer;

    public TraceGuardFilter(
            MeterRegistry registry,
            TraceGuardProperties config,
            PulseRequestMatcher gate,
            PulseEnforcementMode enforcement) {
        this(registry, config, gate, enforcement, Tracer.NOOP);
    }

    public TraceGuardFilter(
            MeterRegistry registry,
            TraceGuardProperties config,
            PulseRequestMatcher gate,
            PulseEnforcementMode enforcement,
            Tracer tracer) {
        this.registry = registry;
        this.config = config;
        this.gate = gate;
        this.enforcement = enforcement;
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
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
        boolean hasTrace = hasTraceContext(request, tracer);

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

        if (config.failOnMissing() && !enforcement.dryRun()) {
            throw new ServletException("Pulse TraceGuard: incoming request is missing trace-context headers ("
                    + TRACEPARENT
                    + " / "
                    + B3_TRACE_ID
                    + "). Configure your upstream caller to propagate context, or set "
                    + "pulse.trace-guard.fail-on-missing=false.");
        }
        log.warn(
                "Pulse TraceGuard: missing trace context for route={}{}",
                routeTag,
                enforcement.dryRun() ? " (dry-run: would have rejected)" : "");
        chain.doFilter(request, response);
    }

    private static boolean hasTraceContext(HttpServletRequest request, Tracer tracer) {
        if (PulseSpans.hasValidContext(tracer)) return true;
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
