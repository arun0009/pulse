package io.github.arun0009.pulse.dependencies;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the per-request fan-out filter respects the 1.1 {@code enabled-when} gate.
 * When the matcher rejects a request, the thread-local must not be opened (so any nested
 * outbound recorder calls are no-ops at the fan-out layer too) and no distributions are
 * recorded.
 */
class RequestFanoutFilterEnabledWhenTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final DependenciesProperties config = new DependenciesProperties(
            true,
            Map.of(),
            "unknown",
            5,
            PulseRequestMatcherProperties.empty(),
            new DependenciesProperties.Health(true, List.of(), 0.05, false));

    @AfterEach
    void clear() {
        RequestFanout.end();
    }

    @Test
    void matcher_rejects_request_no_distribution_recorded() throws Exception {
        RequestFanoutFilter filter = new RequestFanoutFilter(
                registry, config, request -> !"synthetic".equals(request.getHeader("x-pulse-source")));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("x-pulse-source", "synthetic");

        FilterChain chain = (req, resp) -> {
            // No scope opened: any record call must be a no-op at the snapshot layer.
            RequestFanout.record("payment-service");
            assertThat(RequestFanout.peek())
                    .as("scope must not be opened when matcher rejects")
                    .isNull();
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(registry.find("pulse.request.fan_out").summary())
                .as("rejected request must not record a fan-out distribution")
                .isNull();
        assertThat(registry.find("pulse.request.distinct_dependencies").summary())
                .isNull();
    }
}
