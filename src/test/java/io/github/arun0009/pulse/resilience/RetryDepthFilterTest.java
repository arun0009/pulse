package io.github.arun0009.pulse.resilience;

import io.github.arun0009.pulse.core.ContextKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class RetryDepthFilterTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final RetryProperties config = new RetryProperties(true, "Pulse-Retry-Depth", 3);
    private final RetryDepthFilter filter = new RetryDepthFilter(config, registry);

    @AfterEach
    void cleanup() {
        MDC.clear();
        RetryDepthContext.clear();
    }

    @Test
    void seedsContextAndMdcFromInboundHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/orders");
        req.addHeader("Pulse-Retry-Depth", "2");
        FilterChain chain = (request, response) -> {
            assertThat(MDC.get(ContextKeys.RETRY_DEPTH)).isEqualTo("2");
            assertThat(RetryDepthContext.current()).isEqualTo(2);
        };

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(MDC.get(ContextKeys.RETRY_DEPTH)).isNull();
        assertThat(RetryDepthContext.current()).isZero();
    }

    @Test
    void emitsAmplificationCounterWhenThresholdCrossed() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/orders/123");
        req.addHeader("Pulse-Retry-Depth", "5");
        // The matched route is normally set by Spring's HandlerMapping during dispatch; simulate
        // it inside the chain so the filter's deferred emission picks up the bounded route tag.
        FilterChain chain = (request, response) ->
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(registry.find("pulse.retry.amplification")
                        .tag("endpoint", "/orders/{id}")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void amplificationFallsBackToOtherWhenRouteUnmatched() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/no-route-bound");
        req.addHeader("Pulse-Retry-Depth", "5");

        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {});

        assertThat(registry.find("pulse.retry.amplification")
                        .tag("endpoint", "other")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void doesNotEmitBelowThreshold() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/orders");
        req.addHeader("Pulse-Retry-Depth", "2");

        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {});

        assertThat(registry.find("pulse.retry.amplification").counter()).isNull();
    }

    @Test
    void absentHeaderLeavesContextEmpty() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/orders");
        FilterChain chain = (request, response) -> {
            assertThat(MDC.get(ContextKeys.RETRY_DEPTH)).isNull();
            assertThat(RetryDepthContext.current()).isZero();
        };

        filter.doFilter(req, new MockHttpServletResponse(), chain);
    }

    @Test
    void restoresPreviousMdcAndContextOnExit() throws Exception {
        MDC.put(ContextKeys.RETRY_DEPTH, "9");
        RetryDepthContext.set(9);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/orders");
        req.addHeader("Pulse-Retry-Depth", "2");

        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {});

        assertThat(MDC.get(ContextKeys.RETRY_DEPTH)).isEqualTo("9");
        assertThat(RetryDepthContext.current()).isEqualTo(9);
    }
}
