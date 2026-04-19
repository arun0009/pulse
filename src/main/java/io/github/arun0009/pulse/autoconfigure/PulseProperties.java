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
 *   cardinality.max-tag-values-per-meter: 1000
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
        @DefaultValue Slo slo,
        @DefaultValue Health health,
        @DefaultValue Shutdown shutdown,
        @DefaultValue Jobs jobs,
        @DefaultValue Db db) {

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

    /**
     * Trace sampler configuration.
     *
     * <p>The default is {@code ParentBased(TraceIdRatioBased(probability))}: 100% in dev, set
     * {@code probability} to {@code 0.1}–{@code 0.05} in production.
     *
     * <p>{@link #preferSamplingOnError()} composes a best-effort error-biased sampler on top:
     * when a span has its status set to {@code ERROR} or carries an exception attribute, Pulse
     * marks it sampled even if the parent's flag would have dropped it. This is an in-process
     * heuristic — true tail sampling requires the OpenTelemetry Collector — but it dramatically
     * raises the recall on errors with negligible volume cost. See the README "Sampling" section
     * for the full caveat list.
     */
    public record Sampling(
            @DefaultValue("1.0") double probability,
            @DefaultValue("true") boolean preferSamplingOnError) {}

    /**
     * MDC + OTel context propagation across {@code @Async}, {@code @Scheduled}, and other thread
     * hops.
     *
     * <p>By default Pulse <em>only</em> exposes a {@link org.springframework.core.task.TaskDecorator}
     * bean — Spring Boot's {@code TaskExecutionAutoConfiguration} auto-applies it to the standard
     * {@code applicationTaskExecutor}, so apps keep Boot's pool sizing and {@code
     * spring.task.execution.pool.*} configuration. Setting {@link #dedicatedExecutor()} to
     * {@code true} additionally registers a dedicated {@code pulseDedicatedExecutor} bean for
     * isolation needs.
     */
    public record Async(
            @DefaultValue("true") boolean propagationEnabled,
            @DefaultValue("false") boolean dedicatedExecutor,
            @DefaultValue("8") int corePoolSize,
            @DefaultValue("32") int maxPoolSize,
            @DefaultValue("100") int queueCapacity,
            @DefaultValue("pulse-") String threadNamePrefix,
            @DefaultValue("true") boolean scheduledPropagationEnabled) {}

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

    /**
     * Pulse-supplied health indicators. The OTel exporter health indicator reports a degraded
     * state when no span has been successfully exported within {@link #otelExporterStaleAfter()}.
     * Disable if your service intentionally stays idle for long stretches.
     */
    public record Health(
            @DefaultValue("true") boolean otelExporterEnabled,
            @DefaultValue("5m") Duration otelExporterStaleAfter) {}

    /**
     * Lifecycle behavior on JVM shutdown. When enabled, Pulse blocks shutdown for up to
     * {@link #otelFlushTimeout()} so the OTel BatchSpanProcessor can flush queued spans —
     * without this, the last few hundred spans before a rolling deploy are lost.
     */
    public record Shutdown(
            @DefaultValue("true") boolean otelFlushEnabled,
            @DefaultValue("10s") Duration otelFlushTimeout) {}

    /**
     * Background-job observability — wraps every {@code @Scheduled} method (and any other
     * {@link Runnable} routed through Spring's {@link org.springframework.scheduling.TaskScheduler})
     * with metrics + a registry that powers the {@code jobs} health indicator.
     *
     * <p>Metrics emitted (cardinality bounded by your {@code @Scheduled} method count):
     * {@code pulse.jobs.executions{job, outcome}}, {@code pulse.jobs.duration{job, outcome}},
     * {@code pulse.jobs.in_flight{job}}.
     *
     * <p>{@link #failureGracePeriod()} controls when {@code JobsHealthIndicator} flips a job to
     * {@code DOWN}: any job that has been observed running but has not succeeded within this
     * window is considered stuck. The default of 1 hour is generous enough to cover daily and
     * hourly jobs without false-positiving five-minute jobs that briefly fail and recover.
     * Tighten per environment if you run sub-minute jobs.
     */
    public record Jobs(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("true") boolean healthIndicatorEnabled,
            @DefaultValue("1h") Duration failureGracePeriod) {}

    /**
     * Database observability — wires a Hibernate {@code StatementInspector} that counts every
     * prepared SQL statement against a per-request scope and emits:
     *
     * <ul>
     *   <li>{@code pulse.db.statements_per_request{endpoint}} — distribution summary so an
     *       operator can see p50/p95/max statements per endpoint.
     *   <li>{@code pulse.db.n_plus_one.suspect{endpoint}} — counter that fires when a single
     *       request prepares more than {@link #nPlusOneThreshold()} statements, plus a span
     *       event and a structured WARN log line carrying {@code traceId}.
     * </ul>
     *
     * <p>Slow queries are routed through Hibernate's built-in {@code org.hibernate.SQL_SLOW}
     * logger; Pulse seeds the {@code hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS}
     * property to {@link #slowQueryThreshold()}. Because the Pulse JSON layout already adds
     * {@code traceId}/{@code requestId}/{@code service} to every line, the slow-query log is
     * automatically correlated with the trace that issued the query — no extra plumbing
     * required.
     *
     * <p>The default {@link #nPlusOneThreshold()} of {@code 50} is empirically gentle: it catches
     * the classic "loop fetching detail rows" anti-pattern without firing for legitimately
     * statement-heavy endpoints (batch jobs, schema migrations, complex reports). Tighten in
     * production where 20-30 statements should already be a red flag, or relax for analytical
     * services. Setting {@code enabled=false} removes the inspector and the filter altogether
     * (zero overhead for apps that opt out).
     */
    public record Db(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("50") int nPlusOneThreshold,
            @DefaultValue("500ms") Duration slowQueryThreshold) {}
}
