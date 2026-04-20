package io.github.arun0009.pulse.exception;

import io.github.arun0009.pulse.core.ContextKeys;
import io.github.arun0009.pulse.core.PulseRequestMatcher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the 1.1 {@code enabled-when} gate on {@link PulseExceptionHandler}: when the matcher
 * rejects a request the handler still returns a valid {@link ProblemDetail} (so the caller is
 * not left with a Spring default error page) but skips fingerprinting, the MDC stamp, the span
 * attribute, and the {@code pulse.errors.unhandled} counter increment.
 */
class PulseExceptionHandlerEnabledWhenTest {

    @BeforeEach
    void seed() {
        MDC.put(ContextKeys.REQUEST_ID, "req-1");
        MDC.put(ContextKeys.TRACE_ID, "trace-1");
    }

    @AfterEach
    void clear() {
        MDC.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void rejecting_matcher_returns_baseline_problem_without_fingerprint_or_metric() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PulseExceptionHandler handler =
                new PulseExceptionHandler(registry, ErrorFingerprintStrategy.DEFAULT, request -> false, Tracer.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/synthetic");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ProblemDetail problem = handler.handle(new IllegalStateException("boom"));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getProperties())
                .as("baseline problem still surfaces request/trace ids; fingerprint is suppressed")
                .doesNotContainKey("errorFingerprint");
        assertThat(MDC.get(PulseExceptionHandler.FINGERPRINT_MDC_KEY))
                .as("fingerprint must not leak into MDC when matcher rejected the request")
                .isNull();
        assertThat(registry.find("pulse.errors.unhandled").counter())
                .as("matcher rejected; no error counter should be incremented")
                .isNull();
    }

    @Test
    void accepting_matcher_behaves_exactly_like_pre_1_1() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PulseExceptionHandler handler = new PulseExceptionHandler(
                registry, ErrorFingerprintStrategy.DEFAULT, PulseRequestMatcher.ALWAYS, Tracer.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ProblemDetail problem = handler.handle(new IllegalStateException("boom"));

        assertThat(problem.getProperties()).containsKey("errorFingerprint");
        assertThat(registry.find("pulse.errors.unhandled").counter().count()).isEqualTo(1.0);
    }

    @Test
    void custom_fingerprint_strategy_is_used_when_active() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PulseExceptionHandler handler = new PulseExceptionHandler(
                registry, throwable -> "sentry-event-42", PulseRequestMatcher.ALWAYS, Tracer.NOOP);

        ProblemDetail problem = handler.handle(new IllegalStateException("boom"));

        assertThat(problem.getProperties())
                .as("custom strategy bean must replace the default SHA-256 fingerprint")
                .containsEntry("errorFingerprint", "sentry-event-42");
        assertThat(registry.find("pulse.errors.unhandled")
                        .tag("fingerprint", "sentry-event-42")
                        .counter())
                .isNotNull();
    }
}
