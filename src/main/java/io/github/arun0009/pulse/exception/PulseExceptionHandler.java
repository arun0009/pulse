package io.github.arun0009.pulse.exception;

import io.github.arun0009.pulse.core.ContextKeys;
import io.github.arun0009.pulse.core.PulseRequestMatcher;
import io.github.arun0009.pulse.tracing.internal.PulseSpans;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;

/**
 * Global RFC 7807 exception handler that:
 *
 * <ul>
 *   <li>marks the active span as errored ({@code Span.error(throwable)}) — the bridge sets
 *       the bridge-specific status (OTel: {@code ERROR}; Brave: {@code error} tag);
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

    private final @Nullable MeterRegistry registry;
    private final ErrorFingerprintStrategy fingerprintStrategy;
    private final PulseRequestMatcher gate;
    private final Tracer tracer;

    /**
     * Single primary constructor. Callers that want the pre-2.0 defaults should pass
     * {@code null} for {@code registry}, {@link ErrorFingerprintStrategy#DEFAULT},
     * {@link PulseRequestMatcher#ALWAYS}, and {@link Tracer#NOOP}. Auto-config builds the
     * production wiring; tests that need minimal setup can use
     * {@link #withDefaults(MeterRegistry)}.
     */
    public PulseExceptionHandler(
            @Nullable MeterRegistry registry,
            ErrorFingerprintStrategy fingerprintStrategy,
            PulseRequestMatcher gate,
            Tracer tracer) {
        this.registry = registry;
        this.fingerprintStrategy = fingerprintStrategy;
        this.gate = gate;
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
    }

    /**
     * Test factory that materialises the pre-2.0 default wiring — {@link ErrorFingerprintStrategy#DEFAULT},
     * {@link PulseRequestMatcher#ALWAYS}, and {@link Tracer#NOOP} — around an optional
     * {@link MeterRegistry}. Keeps test code terse without bloating the class with multiple
     * convenience constructors.
     */
    public static PulseExceptionHandler withDefaults(@Nullable MeterRegistry registry) {
        return new PulseExceptionHandler(
                registry, ErrorFingerprintStrategy.DEFAULT, PulseRequestMatcher.ALWAYS, Tracer.NOOP);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handle(Exception ex) {
        if (!shouldHandle()) {
            return baselineProblem(null);
        }

        String chainResult = fingerprintStrategy.fingerprint(ex);
        // Defensive: the wired strategy is normally the chain composite, which terminates in
        // ExceptionFingerprint.of(...) and is guaranteed non-null. We still coalesce here so a
        // misconfigured deployment that removed the terminal link doesn't crash the handler.
        String fingerprint = chainResult != null ? chainResult : ExceptionFingerprint.of(ex);
        MDC.put(FINGERPRINT_MDC_KEY, fingerprint);

        Span span = PulseSpans.recordable(tracer);
        if (span != null) {
            span.tag(FINGERPRINT_SPAN_ATTRIBUTE, fingerprint);
            span.error(ex);
        }

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

        return baselineProblem(fingerprint);
    }

    private boolean shouldHandle() {
        if (gate == PulseRequestMatcher.ALWAYS) return true;
        HttpServletRequest request = currentRequest();
        // Fail-open: if we can't see the request (e.g. a non-servlet entry point), the feature
        // still runs. Matching is a conscious opt-in, not a silent kill switch.
        return request == null || gate.matches(request);
    }

    private static @Nullable HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) return sra.getRequest();
        return null;
    }

    private ProblemDetail baselineProblem(@Nullable String fingerprint) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An internal error occurred. Reference: " + MDC.get(ContextKeys.REQUEST_ID));
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("urn:pulse:error:internal"));
        problem.setProperty("requestId", MDC.get(ContextKeys.REQUEST_ID));
        problem.setProperty("traceId", MDC.get(ContextKeys.TRACE_ID));
        if (fingerprint != null) {
            problem.setProperty("errorFingerprint", fingerprint);
        }
        return problem;
    }
}
