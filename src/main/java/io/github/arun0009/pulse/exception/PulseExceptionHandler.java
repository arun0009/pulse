package io.github.arun0009.pulse.exception;

import io.github.arun0009.pulse.core.ContextKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Global RFC 7807 exception handler that:
 *
 * <ul>
 *   <li>marks the active OTel span as {@code ERROR} with the exception recorded;
 *   <li>computes a stable {@link ExceptionFingerprint} and attaches it as a span attribute
 *       ({@code error.fingerprint}), MDC key, and {@code ProblemDetail} property — so dashboards
 *       can cluster recurrences of the same bug across deploys and hosts;
 *   <li>increments {@code pulse.errors.unhandled} (tagged by exception class and fingerprint, both
 *       protected by the cardinality firewall);
 *   <li>logs at ERROR with full MDC context (traceId, requestId, userId, fingerprint);
 *   <li>returns a {@link ProblemDetail} that surfaces {@code requestId}, {@code traceId}, and
 *       {@code errorFingerprint} so support engineers can locate the trace and bug instantly.
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class PulseExceptionHandler {

    public static final String FINGERPRINT_MDC_KEY = "errorFingerprint";
    public static final String FINGERPRINT_SPAN_ATTRIBUTE = "error.fingerprint";

    private static final Logger log = LoggerFactory.getLogger(PulseExceptionHandler.class);
    private static final AttributeKey<String> FINGERPRINT_KEY = AttributeKey.stringKey(FINGERPRINT_SPAN_ATTRIBUTE);

    private final @Nullable MeterRegistry registry;

    public PulseExceptionHandler() {
        this(null);
    }

    public PulseExceptionHandler(@Nullable MeterRegistry registry) {
        this.registry = registry;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handle(Exception ex) {
        String fingerprint = ExceptionFingerprint.of(ex);
        MDC.put(FINGERPRINT_MDC_KEY, fingerprint);

        Span span = Span.current();
        span.setStatus(StatusCode.ERROR, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        span.setAttribute(FINGERPRINT_KEY, fingerprint);
        span.recordException(ex);

        if (registry != null) {
            Counter.builder("pulse.errors.unhandled")
                    .description("Unhandled exceptions caught by Pulse, tagged by class + stable fingerprint")
                    .tag("exception", ex.getClass().getSimpleName())
                    .tag("fingerprint", fingerprint)
                    .register(registry)
                    .increment();
        }

        log.error(
                "Unhandled exception [user={}, request={}, correlation={}, fingerprint={}]: {}",
                MDC.get(ContextKeys.USER_ID),
                MDC.get(ContextKeys.REQUEST_ID),
                MDC.get(ContextKeys.CORRELATION_ID),
                fingerprint,
                ex.getMessage(),
                ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An internal error occurred. Reference: " + MDC.get(ContextKeys.REQUEST_ID));
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("urn:pulse:error:internal"));
        problem.setProperty("requestId", MDC.get(ContextKeys.REQUEST_ID));
        problem.setProperty("traceId", MDC.get(ContextKeys.TRACE_ID));
        problem.setProperty("errorFingerprint", fingerprint);
        return problem;
    }
}
