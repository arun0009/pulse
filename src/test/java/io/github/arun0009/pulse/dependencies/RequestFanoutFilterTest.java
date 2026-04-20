package io.github.arun0009.pulse.dependencies;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFanoutFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void recordsFanOutAndDistinctDepsForRequest() throws Exception {
        RequestFanoutFilter filter = newFilter(20);
        FilterChain chain = (req, res) -> {
            RequestFanout.record("payment-service");
            RequestFanout.record("payment-service");
            RequestFanout.record("inventory-service");
        };

        filter.doFilter(new MockHttpServletRequest("GET", "/checkout"), new MockHttpServletResponse(), chain);

        assertThat(registry.get("pulse.request.fan_out").summary().max()).isEqualTo(3.0);
        assertThat(registry.get("pulse.request.distinct_dependencies").summary().max())
                .isEqualTo(2.0);
        assertThat(registry.find("pulse.request.fan_out_high").counter()).isNull();
    }

    @Test
    void firesFanOutHighCounterAboveThreshold() throws Exception {
        RequestFanoutFilter filter = newFilter(2);
        FilterChain chain = (req, res) -> {
            RequestFanout.record("a");
            RequestFanout.record("b");
            RequestFanout.record("c");
        };

        filter.doFilter(new MockHttpServletRequest("GET", "/checkout"), new MockHttpServletResponse(), chain);

        assertThat(registry.get("pulse.request.fan_out_high").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordsZeroFanoutForRequestsWithoutOutboundCalls() throws Exception {
        RequestFanoutFilter filter = newFilter(20);
        FilterChain chain = (req, res) -> {};

        filter.doFilter(new MockHttpServletRequest("GET", "/health"), new MockHttpServletResponse(), chain);

        assertThat(registry.get("pulse.request.fan_out").summary().count()).isEqualTo(1L);
        assertThat(registry.get("pulse.request.fan_out").summary().max()).isEqualTo(0.0);
    }

    private RequestFanoutFilter newFilter(int threshold) {
        return new RequestFanoutFilter(
                registry,
                new DependenciesProperties(
                        true,
                        Map.of(),
                        "unknown",
                        threshold,
                        io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties.empty(),
                        new DependenciesProperties.Health(true, java.util.List.of(), 0.05, false)));
    }
}
