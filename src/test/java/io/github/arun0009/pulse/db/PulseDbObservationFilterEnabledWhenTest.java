package io.github.arun0009.pulse.db;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the 1.1 {@code enabled-when} gate on {@link PulseDbObservationFilter}: when the
 * matcher rejects a request the per-request statement scope is never opened, so the
 * {@code pulse.db.statements_per_request} distribution is not recorded for that endpoint and the
 * thread-local is guaranteed not to leak.
 */
class PulseDbObservationFilterEnabledWhenTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final DbProperties config =
            new DbProperties(true, 5, Duration.ofMillis(500), PulseRequestMatcherProperties.empty());

    @AfterEach
    void clear() {
        DbObservationContext.clear();
    }

    @Test
    void matcher_rejects_request_skips_scope_and_no_metric_is_emitted() throws Exception {
        PulseDbObservationFilter filter = new PulseDbObservationFilter(
                registry, config, request -> !"true".equals(request.getHeader("x-pulse-skip")));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("x-pulse-skip", "true");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders");

        FilterChain chain = (req, resp) -> {
            // Even if the application code tries to record statements on the (non-existent)
            // scope, nothing should land. We just verify the scope was never opened.
            assertThat(DbObservationContext.snapshot())
                    .as("no scope should be opened when matcher rejects")
                    .isNull();
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(registry.find("pulse.db.statements_per_request").summary())
                .as("matcher rejected the request, so no distribution should be recorded")
                .isNull();
    }
}
