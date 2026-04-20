package io.github.arun0009.pulse.exception;

import io.github.arun0009.pulse.core.ContextKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class PulseExceptionHandlerTest {

    @BeforeEach
    void seedMdc() {
        MDC.put(ContextKeys.REQUEST_ID, "req-abc");
        MDC.put(ContextKeys.TRACE_ID, "trace-xyz");
        MDC.put(ContextKeys.USER_ID, "user-7");
        MDC.put(ContextKeys.CORRELATION_ID, "corr-1");
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void buildsRfc7807ProblemDetailWithRequestAndTraceIds() {
        PulseExceptionHandler handler = PulseExceptionHandler.withDefaults(null);

        ProblemDetail problem = handler.handle(new IllegalStateException("boom"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getTitle()).isEqualTo("Internal Server Error");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:pulse:error:internal"));
        assertThat(problem.getDetail()).contains("req-abc");
        assertThat(problem.getProperties()).containsEntry("requestId", "req-abc");
        assertThat(problem.getProperties()).containsEntry("traceId", "trace-xyz");
        assertThat(problem.getProperties()).containsKey("errorFingerprint");
        assertThat((String) problem.getProperties().get("errorFingerprint"))
                .as("fingerprint must be non-empty so dashboards can cluster recurrences")
                .isNotBlank();
    }

    @Test
    void putsFingerprintIntoMdcSoLogAppenderEmitsIt() {
        PulseExceptionHandler handler = PulseExceptionHandler.withDefaults(null);

        handler.handle(new RuntimeException("anything"));

        assertThat(MDC.get(PulseExceptionHandler.FINGERPRINT_MDC_KEY))
                .as("fingerprint must be in MDC for the active log line")
                .isNotBlank();
    }

    @Test
    void incrementsUnhandledErrorCounterTaggedByExceptionAndFingerprint() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PulseExceptionHandler handler = PulseExceptionHandler.withDefaults(registry);

        // Two exceptions thrown from the same site share a stack trace — that is the whole
        // point of the fingerprint, so dashboards see one row that increments, not N+1 rows
        // when the same bug recurs. We loop through a single throw site to guarantee identical
        // top frames between the two handle() invocations.
        for (int i = 0; i < 2; i++) {
            handler.handle(throwIllegalState("attempt " + i));
        }

        var meter = registry.find("pulse.errors.unhandled").counters();
        assertThat(meter)
                .as("a single counter should aggregate same-fingerprint exceptions")
                .hasSize(1);
        assertThat(meter.iterator().next().count()).isEqualTo(2.0);
        assertThat(meter.iterator().next().getId().getTag("exception")).isEqualTo("IllegalStateException");
        assertThat(meter.iterator().next().getId().getTag("fingerprint")).isNotBlank();
    }

    private static IllegalStateException throwIllegalState(String message) {
        return new IllegalStateException(message);
    }

    @Test
    void differentExceptionTypesGetDifferentCounters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PulseExceptionHandler handler = PulseExceptionHandler.withDefaults(registry);

        handler.handle(new IllegalStateException("a"));
        handler.handle(new IllegalArgumentException("b"));

        assertThat(registry.find("pulse.errors.unhandled").counters())
                .as("each exception class should produce its own row in the dashboard")
                .hasSize(2);
    }

    @Test
    void handlesNullExceptionMessageWithoutCrashing() {
        PulseExceptionHandler handler = PulseExceptionHandler.withDefaults(null);

        ProblemDetail problem = handler.handle(new RuntimeException((String) null));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getProperties()).containsKey("errorFingerprint");
    }

    @Test
    void worksWhenMeterRegistryIsAbsent() {
        // withDefaults(null) passes null for MeterRegistry — handler must still build a problem detail.
        PulseExceptionHandler handler = PulseExceptionHandler.withDefaults(null);

        ProblemDetail problem = handler.handle(new IllegalStateException("boom"));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getProperties()).containsKey("errorFingerprint");
    }
}
