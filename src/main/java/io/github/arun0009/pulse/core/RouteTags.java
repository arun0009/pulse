package io.github.arun0009.pulse.core;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Resolves the Spring MVC route template ({@code /users/{id}}) for an in-flight request, or
 * a fixed {@code other} bucket when no route was matched. The matched template is bounded by
 * the application's controller mappings, which makes it safe to use both as a low-cardinality
 * meter tag (mirroring how Spring Boot's {@code WebMvcMetricsFilter} keeps
 * {@code http.server.requests} bounded) and as a value interpolated into log lines: it is not
 * user-controlled, so it cannot carry log-injection payloads.
 *
 * <p>The pattern is set by {@code HandlerMapping} during dispatch, so callers reading it
 * <em>before</em> {@code chain.doFilter} runs will get {@link #UNMATCHED}; defer the read to
 * a {@code finally} block when both behaviours matter.
 */
public final class RouteTags {

    public static final String UNMATCHED = "other";

    private RouteTags() {}

    public static String of(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String s && !s.isBlank()) return s;
        return UNMATCHED;
    }
}
