package io.github.arun0009.pulse.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Centralized configuration for Pulse.
 *
 * <p>All Pulse subsystems are wired through this single record tree so that the {@code
 * /actuator/pulse} endpoint can faithfully report what is on, what is off, and why. Every nested
 * record uses {@link DefaultValue} so a consumer can adopt Pulse with zero {@code application.yml}
 * entries and override only the properties they care about.
 *
 * <pre>
 * pulse:
 *   trace-guard.fail-on-missing: false
 *   cardinality.max-tag-values: 1000
 *   timeout-budget.default-budget: 2s
 *   wide-events.counter-enabled: true
 * </pre>
 */
@ConfigurationProperties(prefix = "pulse")
public record PulseProperties(
        @DefaultValue Context context,
        @DefaultValue TraceGuard traceGuard,
        @DefaultValue Sampling sampling,
        @DefaultValue Async async,
        @DefaultValue Kafka kafka,
        @DefaultValue ExceptionHandler exceptionHandler,
        @DefaultValue Audit audit,
        @DefaultValue Cardinality cardinality,
        @DefaultValue TimeoutBudget timeoutBudget,
        @DefaultValue WideEvents wideEvents,
        @DefaultValue Logging logging,
        @DefaultValue Banner banner,
        @DefaultValue Histograms histograms,
        @DefaultValue Slo slo) {

    /** MDC enrichment from the inbound HTTP request. */
    public record Context(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("X-Request-ID") String requestIdHeader,
            @DefaultValue("X-Correlation-ID") String correlationIdHeader,
            @DefaultValue("X-User-ID") String userIdHeader,
            @DefaultValue("X-Tenant-ID") String tenantIdHeader,
            @DefaultValue("Idempotency-Key") String idempotencyKeyHeader,
            @DefaultValue({}) List<String> additionalHeaders) {}

    /** Detect inbound requests missing trace-context headers. */
    public record TraceGuard(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("false") boolean failOnMissing,

            @DefaultValue({"/actuator", "/health", "/metrics"})
            List<String> excludePathPrefixes) {}

    /** ParentBased(TraceIdRatioBased) sampler. 1.0 = 100% (dev), 0.1 = 10% (prod). */
    public record Sampling(@DefaultValue("1.0") double probability) {}

    /** MDC + OTel context propagation across {@code @Async} and other thread hops. */
    public record Async(
            @DefaultValue("true") boolean propagationEnabled,
            @DefaultValue("true") boolean autoConfigureExecutor,
            @DefaultValue("8") int corePoolSize,
            @DefaultValue("32") int maxPoolSize,
            @DefaultValue("100") int queueCapacity,
            @DefaultValue("pulse-") String threadNamePrefix) {}

    /** Kafka producer/consumer interceptor registration. */
    public record Kafka(@DefaultValue("true") boolean propagationEnabled) {}

    /** RFC 7807 ProblemDetail responses with traceId + requestId surfaced. */
    public record ExceptionHandler(@DefaultValue("true") boolean enabled) {}

    /** Dedicated AUDIT logger routing to a separate appender. */
    public record Audit(@DefaultValue("true") boolean enabled) {}

    /**
     * Cardinality firewall — caps the number of distinct tag values per meter to prevent
     * runaway-tag bill explosions. Excess values bucket to {@code OVERFLOW} and a one-time WARN log
     * line fires.
     */
    public record Cardinality(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1000") int maxTagValuesPerMeter,
            @DefaultValue("OVERFLOW") String overflowValue,
            @DefaultValue({}) List<String> meterPrefixesToProtect,
            @DefaultValue({}) List<String> exemptMeterPrefixes) {}

    /**
     * Timeout-budget propagation — extracts {@code X-Timeout-Ms} on inbound requests, places
     * remaining-budget on OTel baggage, and exposes it via {@code TimeoutBudget#current}.
     * Downstream calls subtract elapsed time so a 2s inbound budget with 800ms spent in business
     * logic gives the next downstream call exactly 1.2s — not the platform default. Inbound
     * headers are clamped to {@link #maximumBudget()} for edge safety.
     */
    public record TimeoutBudget(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("X-Timeout-Ms") String inboundHeader,
            @DefaultValue("X-Timeout-Ms") String outboundHeader,
            @DefaultValue("2s") Duration defaultBudget,
            @DefaultValue("30s") Duration maximumBudget,
            @DefaultValue("50ms") Duration safetyMargin,
            @DefaultValue("100ms") Duration minimumBudget) {}

    /**
     * Wide-event API ({@link io.github.arun0009.pulse.events.SpanEvents}) — one call attaches
     * attributes to the active span, emits a structured INFO log, and (optionally) increments a
     * bounded counter.
     */
    public record WideEvents(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("true") boolean counterEnabled,
            @DefaultValue("true") boolean logEnabled,
            @DefaultValue("pulse.events") String counterName,
            @DefaultValue("event") String logMessagePrefix) {}

    /** Logging integration — JSON layout, PII masking. */
    public record Logging(@DefaultValue("true") boolean piiMaskingEnabled) {}

    /** Startup banner that prints active Pulse subsystems and their settings. */
    public record Banner(@DefaultValue("true") boolean enabled) {}

    /** Histogram + percentile defaults for Spring Boot's standard meters. */
    public record Histograms(
            @DefaultValue("true") boolean enabled,

            @DefaultValue({"http.server.requests", "jdbc.query", "spring.kafka.listener"})
            List<String> meterPrefixes,

            @DefaultValue({"50ms", "100ms", "250ms", "500ms", "1s", "5s"})
            List<Duration> sloBuckets) {}

    /**
     * SLO-as-code. Declare service-level objectives in {@code application.yml}; Pulse renders
     * them at {@code /actuator/pulse/slo} as a Prometheus {@code PrometheusRule} document
     * (recording rules + multi-window burn-rate alerts), ready to {@code kubectl apply -f -}.
     *
     * <pre>
     * pulse:
     *   slo:
     *     objectives:
     *       - name: orders-availability
     *         sli: availability
     *         target: 0.999
     *       - name: orders-latency
     *         sli: latency
     *         target: 0.95
     *         threshold: 500ms
     * </pre>
     *
     * <p>Two SLI flavors are supported out of the box:
     * <ul>
     *   <li>{@code availability} — fraction of non-5xx responses on
     *       {@code http_server_requests_seconds_count}.
     *   <li>{@code latency} — fraction of requests under {@code threshold} on
     *       {@code http_server_requests_seconds_bucket}.
     * </ul>
     */
    public record Slo(
            @DefaultValue("true") boolean enabled,
            @DefaultValue({}) List<Objective> objectives) {

        public record Objective(
                String name,
                @DefaultValue("availability") String sli,
                @DefaultValue("0.999") double target,
                @org.jspecify.annotations.Nullable Duration threshold,
                @DefaultValue("http.server.requests") String meter,
                @DefaultValue({}) List<String> filters) {}
    }
}
