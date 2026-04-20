package io.github.arun0009.pulse.db;

import io.github.arun0009.pulse.core.PulseRequestMatcher;
import io.github.arun0009.pulse.tracing.internal.PulseSpans;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * Servlet filter that wraps each inbound request in a {@link DbObservationContext} scope so
 * Pulse can count Hibernate-prepared SQL statements per request and flag N+1 suspects.
 *
 * <p>For every request:
 *
 * <ol>
 *   <li>Begin a scope tagged with the matched URI template (low-cardinality endpoint name from
 *       Spring's {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}). When the template is
 *       not yet known (request hasn't reached the handler mapping at filter time), Pulse falls
 *       back to {@code request.getMethod() + " " + request.getRequestURI()} stripped of common
 *       id-shaped path segments. We deliberately accept some imprecision here over a guaranteed
 *       cardinality blowup.
 *   <li>Run the chain.
 *   <li>Read the per-request snapshot, emit {@code pulse.db.statements_per_request{endpoint}}
 *       (a {@link DistributionSummary} so quantiles + max land in Prometheus), and — if the
 *       count exceeds {@code pulse.db.n-plus-one-threshold} — increment
 *       {@code pulse.db.n_plus_one.suspect{endpoint}}, attach a span event, and log a single
 *       structured warning. The warning includes {@code traceId} (Pulse's existing logging
 *       layout adds it automatically), the endpoint, the count, and the per-verb breakdown so
 *       an engineer can identify the runaway loop without reproducing the request.
 *   <li>Always clear the thread-local in {@code finally} so a pooled request thread doesn't
 *       leak the previous scope into the next request.
 * </ol>
 *
 * <p>Runs <em>after</em> {@code PulseRequestContextFilter} so MDC/tracing context is already
 * established when the warning fires; that is what makes the log line correlatable to the
 * trace.
 */
public class PulseDbObservationFilter extends OncePerRequestFilter implements Ordered {

    /** Sit between request-context (MDC seeding) and the application controllers. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 200;

    private static final Logger log = LoggerFactory.getLogger("pulse.db.n-plus-one");

    private final MeterRegistry meterRegistry;
    private final DbProperties config;
    private final PulseRequestMatcher gate;
    private final Tracer tracer;

    public PulseDbObservationFilter(MeterRegistry meterRegistry, DbProperties config) {
        this(meterRegistry, config, PulseRequestMatcher.ALWAYS, Tracer.NOOP);
    }

    public PulseDbObservationFilter(MeterRegistry meterRegistry, DbProperties config, PulseRequestMatcher gate) {
        this(meterRegistry, config, gate, Tracer.NOOP);
    }

    public PulseDbObservationFilter(
            MeterRegistry meterRegistry, DbProperties config, PulseRequestMatcher gate, Tracer tracer) {
        this.meterRegistry = meterRegistry;
        this.config = config;
        this.gate = gate;
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
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
        String endpoint = resolveEndpoint(request);
        DbObservationContext.begin(endpoint);
        try {
            chain.doFilter(request, response);
        } finally {
            try {
                publish(endpoint);
            } finally {
                DbObservationContext.clear();
            }
        }
    }

    private void publish(String endpoint) {
        DbObservationContext.Snapshot snap = DbObservationContext.snapshot();
        if (snap == null || snap.statementCount() == 0) return;

        DistributionSummary.builder("pulse.db.statements_per_request")
                .description("SQL statements prepared per inbound request, tagged by endpoint")
                .baseUnit("statements")
                .tags(Tags.of("endpoint", endpoint))
                .register(meterRegistry)
                .record(snap.statementCount());

        int threshold = config.nPlusOneThreshold();
        if (snap.statementCount() < threshold) return;

        meterRegistry
                .counter("pulse.db.n_plus_one.suspect", Tags.of("endpoint", endpoint))
                .increment();

        Span span = PulseSpans.recordable(tracer);
        if (span != null) {
            // TODO(phase 4e): per-event attributes — Micrometer's Span has no addEvent(name, attrs)
            // overload, so the attributes land on the parent span instead of the event. The
            // Observation refactor will move this to a PulseDbObservationContext where handlers
            // can attach event-scoped attributes.
            span.event("pulse.db.n_plus_one.suspect");
            span.tag("pulse.db.statements", snap.statementCount());
            span.tag("pulse.db.endpoint", endpoint);
        }

        // Single structured WARN line. Pulse's JSON layout will add trace_id/span_id/service so
        // an SRE can pivot from the alert straight to the trace.
        log.warn(
                "N+1 suspect: {} statements on {} (threshold={}); breakdown={}",
                snap.statementCount(),
                endpoint,
                threshold,
                snap.countsByVerb());
    }

    /**
     * Pulls Spring MVC's matched URI template (e.g. {@code /orders/{id}}) so the metric tag is
     * one entry per route, not one per id. Falls back to a sanitized method+path when no
     * template is present (e.g. for static resources or filters that fire before mapping).
     */
    static String resolveEndpoint(HttpServletRequest request) {
        Object template = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (template instanceof String s && !s.isEmpty()) {
            return request.getMethod() + " " + s;
        }
        // Fallback. The matched pattern attribute is normally not set during the filter's pre-chain
        // pass — Spring MVC sets it inside the DispatcherServlet — so the publish() call after
        // chain.doFilter will see it. This branch is for the edge cases where the request never
        // reached the dispatcher.
        return request.getMethod() + " " + sanitize(request.getRequestURI());
    }

    /**
     * Replaces obvious id-shaped path segments (numeric, UUID-shaped) with {@code {id}} so the
     * fallback endpoint tag does not blow cardinality. Conservative — only collapses the
     * patterns we are sure about.
     */
    private static String sanitize(String path) {
        if (path == null || path.isEmpty()) return "/";
        String[] parts = path.split("/", -1);
        StringBuilder sb = new StringBuilder(path.length());
        for (String part : parts) {
            if (part.isEmpty()) {
                sb.append('/');
                continue;
            }
            if (looksLikeId(part)) {
                sb.append('/').append("{id}");
            } else {
                sb.append('/').append(part);
            }
        }
        // Collapse any leading double-slash from the first split iteration.
        if (sb.length() > 1 && sb.charAt(0) == '/' && sb.charAt(1) == '/') {
            sb.deleteCharAt(0);
        }
        return sb.toString();
    }

    private static boolean looksLikeId(String s) {
        if (s.length() >= 8 && s.length() <= 36) {
            int dashes = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '-') dashes++;
                else if (!isHexOrDigit(c)) return false;
            }
            if (dashes == 4 && s.length() == 36) return true; // UUID
        }
        // Pure numeric id (e.g. /orders/12345)
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return !s.isEmpty();
    }

    private static boolean isHexOrDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
