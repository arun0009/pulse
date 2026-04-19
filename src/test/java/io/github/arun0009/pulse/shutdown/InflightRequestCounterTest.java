package io.github.arun0009.pulse.shutdown;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class InflightRequestCounterTest {

    @Test
    void incrementsAroundFilterChain() throws ServletException, IOException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InflightRequestCounter counter = new InflightRequestCounter(registry);

        FilterChain assertingChain = new FilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                assertThat(counter.current()).isEqualTo(1);
            }
        };

        counter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), assertingChain);
        assertThat(counter.current()).isZero();
    }

    @Test
    void exposesGauge() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InflightRequestCounter counter = new InflightRequestCounter(registry);

        counter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(registry.get("pulse.shutdown.inflight").gauge().value()).isZero();
    }
}
