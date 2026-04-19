package io.github.arun0009.pulse.shutdown;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks the count of HTTP requests currently in flight on this JVM. Used by
 * {@link PulseDrainObservabilityLifecycle} to observe how long graceful shutdown takes to drain
 * and whether anything was still mid-flight when the deadline elapsed.
 *
 * <p>Exposed as the {@code pulse.shutdown.inflight} gauge so dashboards can show the current
 * occupancy independent of shutdown.
 */
public final class InflightRequestCounter extends OncePerRequestFilter {

    public static final String METRIC_NAME = "pulse.shutdown.inflight";

    private final AtomicInteger inflight = new AtomicInteger();

    public InflightRequestCounter(MeterRegistry registry) {
        Gauge.builder(METRIC_NAME, inflight, AtomicInteger::doubleValue)
                .description("HTTP requests currently in flight on this JVM")
                .register(registry);
    }

    public int current() {
        return inflight.get();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        inflight.incrementAndGet();
        try {
            chain.doFilter(request, response);
        } finally {
            inflight.decrementAndGet();
        }
    }
}
