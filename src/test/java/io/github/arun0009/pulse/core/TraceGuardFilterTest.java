package io.github.arun0009.pulse.core;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherFactory;
import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import io.github.arun0009.pulse.enforcement.PulseEnforcementMode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targets the 1.1 {@code enabled-when} integration: the filter must short-circuit (no metrics
 * emitted, chain still invoked) when the matcher rejects a request, and must continue working
 * exactly like before for the matcher's default-{@code ALWAYS} case.
 */
class TraceGuardFilterTest {

    private static final TraceGuardProperties CONFIG_DEFAULT = new TraceGuardProperties(
            true,
            false,
            List.of("/actuator", "/health", "/metrics"),
            new PulseRequestMatcherProperties(Map.of(), Map.of(), Map.of(), List.of(), List.of(), null));

    private static final TraceGuardProperties CONFIG_SKIP_TEST_CLIENT = new TraceGuardProperties(
            true,
            false,
            List.of("/actuator", "/health", "/metrics"),
            new PulseRequestMatcherProperties(
                    Map.of(), Map.of("client-id", "test-client-id"), Map.of(), List.of(), List.of(), null));

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final PulseRequestMatcherFactory matcherFactory =
            new PulseRequestMatcherFactory(new DefaultListableBeanFactory());
    private final PulseEnforcementMode enforcing = new PulseEnforcementMode(PulseEnforcementMode.Mode.ENFORCING);

    @Test
    void without_enabled_when_filter_emits_missing_counter_for_traceless_request() throws Exception {
        TraceGuardFilter filter = new TraceGuardFilter(
                registry, CONFIG_DEFAULT, matcherFactory.build("trace-guard", CONFIG_DEFAULT.enabledWhen()), enforcing);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        FilterChain chain = (req, resp) -> chainInvoked.set(true);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chainInvoked).isTrue();
        assertThat(registry.find("pulse.trace.missing").counter().count()).isEqualTo(1.0);
    }

    @Test
    void enabled_when_skips_test_client_traffic_entirely_and_emits_no_counter() throws Exception {
        TraceGuardFilter filter = new TraceGuardFilter(
                registry,
                CONFIG_SKIP_TEST_CLIENT,
                matcherFactory.build("trace-guard", CONFIG_SKIP_TEST_CLIENT.enabledWhen()),
                enforcing);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("client-id", "test-client-id");
        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        FilterChain chain = (req, resp) -> chainInvoked.set(true);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chainInvoked)
                .as("downstream chain must still run; we only skip Pulse's own work")
                .isTrue();
        assertThat(registry.find("pulse.trace.missing").counter())
                .as("matcher rejected the request, so no missing counter should be emitted")
                .isNull();
        assertThat(registry.find("pulse.trace.received").counter()).isNull();
    }

    @Test
    void enabled_when_still_evaluates_real_traffic_against_the_guard() throws Exception {
        TraceGuardFilter filter = new TraceGuardFilter(
                registry,
                CONFIG_SKIP_TEST_CLIENT,
                matcherFactory.build("trace-guard", CONFIG_SKIP_TEST_CLIENT.enabledWhen()),
                enforcing);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("client-id", "real-user-42");
        FilterChain chain = (req, resp) -> {};

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(registry.find("pulse.trace.missing").counter().count())
                .as("real traffic without trace-context must still be flagged")
                .isEqualTo(1.0);
    }
}
