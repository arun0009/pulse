package io.github.arun0009.pulse.dependencies;

import io.github.arun0009.pulse.core.PulseRequestMatcher;
import io.github.arun0009.pulse.core.RouteTags;
import io.github.arun0009.pulse.tracing.internal.PulseSpans;
import io.micrometer.core.instrument.Counter;
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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that records the per-request fan-out width as a Micrometer distribution and
 * stamps the totals on the active span. Lives outside the dependency interceptors so the
 * thread-local lifecycle has a single owner: the inbound request.
 *
 * <p>Metrics:
 *
 * <ul>
 *   <li>{@code pulse.request.fan_out} — distribution of outbound calls per inbound request.
 *       Operators alert on a p99 climbing over time (a stable endpoint that grew from 5 to 50
 *       outbound calls is almost always an N+1 caller-side regression).
 *   <li>{@code pulse.request.distinct_dependencies} — distribution of distinct dependencies
 *       touched. Combined with fan-out, this distinguishes "200 calls to the same cache" from
 *       "20 calls each across 10 services".
 *   <li>{@code pulse.request.fan_out_high} — counter that increments when a single request's
 *       outbound calls exceed {@link DependenciesProperties#fanOutWarnThreshold()}. Cheap
 *       Boolean signal for an alert rule.
 * </ul>
 *
 * <p>Span attributes added at the end of every request:
 * {@code pulse.request.fan_out}, {@code pulse.request.distinct_dependencies}.
 *
 * <p>The filter has no effect for endpoints that produce zero outbound calls — we still record
 * the {@code 0} value so the distribution is meaningful (and operators can compute the fraction
 * of requests with any fan-out at all).
 */
public final class RequestFanoutFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestFanoutFilter.class);

    private final MeterRegistry registry;
    private final int fanOutWarnThreshold;
    private final PulseRequestMatcher gate;
    private final Tracer tracer;

    public RequestFanoutFilter(MeterRegistry registry, DependenciesProperties config) {
        this(registry, config, PulseRequestMatcher.ALWAYS, Tracer.NOOP);
    }

    public RequestFanoutFilter(MeterRegistry registry, DependenciesProperties config, PulseRequestMatcher gate) {
        this(registry, config, gate, Tracer.NOOP);
    }

    public RequestFanoutFilter(
            MeterRegistry registry, DependenciesProperties config, PulseRequestMatcher gate, Tracer tracer) {
        this.registry = registry;
        this.fanOutWarnThreshold = config.fanOutWarnThreshold();
        this.gate = gate;
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!gate.matches(request)) {
            chain.doFilter(request, response);
            return;
        }
        RequestFanout.begin();
        try {
            chain.doFilter(request, response);
        } finally {
            RequestFanout.Snapshot snap = RequestFanout.end();
            if (snap != null) {
                DistributionSummary.builder("pulse.request.fan_out")
                        .description("Outbound dependency calls per inbound request")
                        .baseUnit("calls")
                        .register(registry)
                        .record(snap.totalCalls());
                DistributionSummary.builder("pulse.request.distinct_dependencies")
                        .description("Distinct downstream dependencies per inbound request")
                        .baseUnit("dependencies")
                        .register(registry)
                        .record(snap.distinctDependencies());
                if (snap.totalCalls() > fanOutWarnThreshold) {
                    Counter.builder("pulse.request.fan_out_high")
                            .description("Inbound requests whose outbound fan-out exceeded the warn threshold")
                            .tags(Tags.of("threshold", Integer.toString(fanOutWarnThreshold)))
                            .register(registry)
                            .increment();
                    log.warn(
                            "Pulse fan-out width {} exceeded threshold {} for route={}",
                            snap.totalCalls(),
                            fanOutWarnThreshold,
                            RouteTags.of(request));
                }
                Span current = PulseSpans.recordable(tracer);
                if (current != null) {
                    current.tag("pulse.request.fan_out", snap.totalCalls());
                    current.tag("pulse.request.distinct_dependencies", snap.distinctDependencies());
                }
            }
        }
    }
}
