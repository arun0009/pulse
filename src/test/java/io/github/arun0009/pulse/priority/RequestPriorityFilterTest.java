package io.github.arun0009.pulse.priority;

import io.github.arun0009.pulse.core.ContextKeys;
import io.opentelemetry.api.baggage.Baggage;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPriorityFilterTest {

    private static final PriorityProperties CONFIG =
            new PriorityProperties(true, "Pulse-Priority", "normal", true, List.of());

    @AfterEach
    void resetThreadState() {
        MDC.clear();
        RequestPriority.clear();
    }

    @Test
    void writesResolvedPriorityToMdcAndThreadLocal() throws Exception {
        RequestPriorityFilter filter = new RequestPriorityFilter(CONFIG);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Pulse-Priority", "critical");
        FilterChain chain = (request, response) -> {
            assertThat(MDC.get(ContextKeys.PRIORITY)).isEqualTo("critical");
            assertThat(RequestPriority.current()).contains(RequestPriority.CRITICAL);
        };

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(MDC.get(ContextKeys.PRIORITY)).isNull();
        assertThat(RequestPriority.current()).isEmpty();
    }

    @Test
    void mirrorsResolvedPriorityOntoOtelBaggage() throws Exception {
        RequestPriorityFilter filter = new RequestPriorityFilter(CONFIG);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Pulse-Priority", "high");
        FilterChain chain =
                (request, response) -> assertThat(Baggage.current().getEntryValue(ContextKeys.PRIORITY_BAGGAGE_KEY))
                        .isEqualTo("high");

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(Baggage.current().getEntryValue(ContextKeys.PRIORITY_BAGGAGE_KEY))
                .isNull();
    }

    @Test
    void fallsBackToConfiguredDefaultWhenHeaderMissing() throws Exception {
        RequestPriorityFilter filter = new RequestPriorityFilter(CONFIG);
        FilterChain chain = (request, response) -> {
            assertThat(MDC.get(ContextKeys.PRIORITY)).isEqualTo("normal");
            assertThat(Baggage.current().getEntryValue(ContextKeys.PRIORITY_BAGGAGE_KEY))
                    .isEqualTo("normal");
        };

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
    }
}
