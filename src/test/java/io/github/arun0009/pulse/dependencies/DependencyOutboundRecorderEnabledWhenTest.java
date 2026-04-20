package io.github.arun0009.pulse.dependencies;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import io.github.arun0009.pulse.core.PulseRequestMatcher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the 1.1 {@code enabled-when} gate on {@link DependencyOutboundRecorder} when the
 * outbound call happens inside an inbound request scope (the common case for a controller
 * making downstream HTTP calls). Three scenarios are exercised:
 *
 * <ul>
 *   <li>matcher rejects → no metrics are recorded.</li>
 *   <li>matcher accepts → metrics are recorded as before.</li>
 *   <li>no inbound request bound to the thread (e.g. {@code @Scheduled}) → fail-open: metrics
 *       still record, since gating an empty matcher would silently break background traffic.
 *   </li>
 * </ul>
 */
class DependencyOutboundRecorderEnabledWhenTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final DependenciesProperties config = new DependenciesProperties(
            true,
            Map.of(),
            "unknown",
            20,
            PulseRequestMatcherProperties.empty(),
            new DependenciesProperties.Health(true, List.of(), 0.05, false));

    private final DependencyResolver resolver = new DependencyResolver(config);

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void rejecting_matcher_skips_recording_inside_request_scope() {
        DependencyOutboundRecorder recorder =
                new DependencyOutboundRecorder(registry, resolver, resolver, config, request -> !"synthetic"
                        .equals(request.getHeader("x-pulse-source")));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("x-pulse-source", "synthetic");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        recorder.record("payment-service", "GET", 200, null, 1_000_000L);

        assertThat(registry.find("pulse.dependency.requests").counter())
                .as("matcher rejected; no counter should be incremented")
                .isNull();
    }

    @Test
    void accepting_matcher_records_metrics_as_before() {
        DependencyOutboundRecorder recorder =
                new DependencyOutboundRecorder(registry, resolver, resolver, config, PulseRequestMatcher.ALWAYS);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        recorder.record("payment-service", "GET", 200, null, 1_000_000L);

        assertThat(registry.find("pulse.dependency.requests").counter().count()).isEqualTo(1.0);
    }

    @Test
    void no_request_bound_means_fail_open_for_background_traffic() {
        // Scheduled jobs and Kafka consumers don't bind a request to the thread. Pulse must
        // not silently drop their dependency metrics just because the matcher had nothing to
        // evaluate against.
        DependencyOutboundRecorder recorder =
                new DependencyOutboundRecorder(registry, resolver, resolver, config, request -> false);

        recorder.record("payment-service", "GET", 200, null, 1_000_000L);

        assertThat(registry.find("pulse.dependency.requests").counter().count())
                .as("no inbound request bound; matcher must fail-open so background traffic still records")
                .isEqualTo(1.0);
    }
}
