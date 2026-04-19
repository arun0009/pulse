package io.github.arun0009.pulse.exception;

import io.github.arun0009.pulse.core.ContextKeys;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
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
 *   <li>logs at ERROR with full MDC context (traceId, requestId, userId);
 *   <li>returns a {@link ProblemDetail} that surfaces the {@code requestId} and {@code traceId} so
 *       support engineers can locate the trace immediately.
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class PulseExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PulseExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ProblemDetail handle(Exception ex) {
        Span span = Span.current();
        span.setStatus(StatusCode.ERROR, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        span.recordException(ex);

        log.error(
                "Unhandled exception [user={}, request={}, correlation={}]: {}",
                MDC.get(ContextKeys.USER_ID),
                MDC.get(ContextKeys.REQUEST_ID),
                MDC.get(ContextKeys.CORRELATION_ID),
                ex.getMessage(),
                ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An internal error occurred. Reference: " + MDC.get(ContextKeys.REQUEST_ID));
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("urn:pulse:error:internal"));
        problem.setProperty("requestId", MDC.get(ContextKeys.REQUEST_ID));
        problem.setProperty("traceId", MDC.get(ContextKeys.TRACE_ID));
        return problem;
    }
}
