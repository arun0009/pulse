package io.github.arun0009.pulse.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
        @DefaultValue Db db,
        @DefaultValue Resilience resilience,
        @DefaultValue Profiling profiling,
        @DefaultValue Dependencies dependencies,
        @DefaultValue Tenant tenant,
        @DefaultValue Retry retry,
        @DefaultValue Priority priority,
        @DefaultValue ContainerMemory containerMemory,
        @DefaultValue OpenFeature openFeature,
        @DefaultValue Cache cache) {

    /** MDC enrichment from the inbound HTTP request. */
    public record Context(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("X-Request-ID") String requestIdHeader,
            @DefaultValue("X-Correlation-ID") String correlationIdHeader,
            @DefaultValue("X-User-ID") String userIdHeader,
            @DefaultValue("Pulse-Tenant-Id") String tenantIdHeader,
            @DefaultValue("Idempotency-Key") String idempotencyKeyHeader,
            @DefaultValue({}) List<String> additionalHeaders) {}

    /**
     * Detect inbound requests missing trace-context headers.
     *
     * <p>{@link #enabledWhen()} (since 1.1) provides per-request gating: the guard is skipped
     * for requests where the matcher returns {@code false}. Use it to bypass synthetic monitoring
     * traffic, smoke tests, or trusted internal callers without setting {@code enabled=false}
     * globally. The default is an empty matcher, which matches every request — pre-1.1 behaviour.
     *
     * <pre>
     * pulse:
     *   trace-guard:
     *     enabled: true
     *     enabled-when:
     *       header-not-equals:
     *         client-id: test-client-id
     * </pre>
     */
    public record TraceGuard(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("false") boolean failOnMissing,

            @DefaultValue({"/actuator", "/health", "/metrics"})
            List<String> excludePathPrefixes,

            @DefaultValue PulseRequestMatcherProperties enabledWhen) {}

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

    /**
     * Kafka producer/consumer integration.
     *
     * <p>{@link #propagationEnabled()} controls registration of Pulse's producer/consumer record
     * interceptors that mirror MDC + timeout-budget + retry-depth onto record headers (and back
     * out on the consumer side).
     *
     * <p>{@link #consumerTimeLagEnabled()} turns on the time-based consumer-lag gauge. Kafka's
     * native lag metric is reported in <em>messages</em>, which is meaningless without knowing
     * the production rate ("you're 50,000 messages behind" is fine for a high-volume topic and
     * a disaster for a low-volume one). Pulse measures
     * {@code now() - record.timestamp()} for every record processed and exposes it as the
     * {@code pulse.kafka.consumer.time_lag{topic, partition, group}} gauge (with
     * {@code .baseUnit("seconds")} so Prometheus exposition is {@code _seconds}). This is the
     * SLO operators actually want: "the oldest unprocessed message is 47 seconds old".
     *
     * <p>Cardinality is bounded by the {@code (topic, partition, group)} fan-out of <em>this
     * consumer</em>, which is intrinsically capped by Kafka's partition assignment.
     */
    public record Kafka(
            @DefaultValue("true") boolean propagationEnabled,
            @DefaultValue("true") boolean consumerTimeLagEnabled) {}

    /**
     * RFC 7807 ProblemDetail responses with traceId + requestId surfaced.
     *
     * <p>{@link #enabledWhen()} (since 1.1) provides per-request gating: when the matcher returns
     * {@code false} for the current request, Pulse still produces a baseline ProblemDetail (so the
     * caller still gets a structured 500), but skips fingerprinting, the MDC stamp, the span
     * attribute, the metric increment, and the structured WARN log. Use it to keep synthetic
     * monitoring noise out of the {@code pulse.errors.unhandled} counter without disabling
     * fingerprinting globally.
     *
     * <pre>
     * pulse:
     *   exception-handler:
     *     enabled-when:
     *       header-not-equals:
     *         x-pulse-synthetic: "true"
     * </pre>
     */
    public record ExceptionHandler(
            @DefaultValue("true") boolean enabled,
            @DefaultValue PulseRequestMatcherProperties enabledWhen) {}

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
     * Timeout-budget propagation — extracts the configured inbound header (default
     * {@code Pulse-Timeout-Ms}, RFC 6648 — no {@code X-} prefix), places remaining-budget on
     * OTel baggage, and exposes it via {@code TimeoutBudget#current}. Downstream calls subtract
     * elapsed time so a 2s inbound budget with 800ms spent in business logic gives the next
     * downstream call exactly 1.2s — not the platform default. Inbound headers are clamped to
     * {@link #maximumBudget()} for edge safety.
     */
    public record TimeoutBudget(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("Pulse-Timeout-Ms") String inboundHeader,
            @DefaultValue("Pulse-Timeout-Ms") String outboundHeader,
            @DefaultValue("2s") Duration defaultBudget,
            @DefaultValue("30s") Duration maximumBudget,
            @DefaultValue("50ms") Duration safetyMargin,
            @DefaultValue("100ms") Duration minimumBudget,
            @DefaultValue PulseRequestMatcherProperties enabledWhen) {}

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
            @DefaultValue("10s") Duration otelFlushTimeout,
            @DefaultValue Drain drain) {

        /**
         * Drain observability — counts inflight HTTP requests at the moment Spring's
         * {@code SmartLifecycle.stop()} fires, polls until they finish or the deadline
         * elapses, and emits structured before/after metrics so an operator can answer
         * "how long did the rolling deploy take to drain, and did anything get
         * dropped?" without parsing logs by hand.
         *
         * <p>Cooperates with {@code server.shutdown=graceful} — Pulse's filter only
         * <em>observes</em> the in-flight count; the actual draining is Spring Boot's
         * job. Setting {@code enabled=false} removes both the filter and the lifecycle
         * bean.
         */
        public record Drain(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("30s") Duration timeout) {}
    }

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
            @DefaultValue("500ms") Duration slowQueryThreshold,
            @DefaultValue PulseRequestMatcherProperties enabledWhen) {}

    /**
     * Resilience4j auto-instrumentation. When the application registers a
     * {@code CircuitBreakerRegistry}, {@code RetryRegistry}, or {@code BulkheadRegistry},
     * Pulse attaches event consumers that turn state transitions, retries, and rejections
     * into Micrometer counters + span events + structured log lines. No code changes required
     * on the consumer's @CircuitBreaker / @Retry annotated methods.
     *
     * <p>Setting {@code enabled=false} disables every observer at once. Each individual
     * observer is also gated on its registry being present, so a service that only uses
     * circuit breakers does not pay for retry or bulkhead wiring.
     */
    public record Resilience(@DefaultValue("true") boolean enabled) {}

    /**
     * Continuous-profiling correlation. When enabled (default), Pulse stamps
     * {@code profile.id} and {@code pyroscope.profile_id} attributes on every span using the
     * trace id, so any APM that ingests the attributes can render a one-click "Open profile"
     * link without per-vendor configuration.
     *
     * <p>Setting {@link #pyroscopeUrl()} to your Pyroscope (or compatible) host adds a
     * root-span-only {@code pulse.profile.url} attribute pointing directly at the flame graph
     * for the trace's time window — clickable in Tempo, Jaeger, Zipkin, and Grafana traces UI.
     *
     * <p>Pulse never bundles or starts a profiler. {@link io.github.arun0009.pulse.profiling.PyroscopeDetector}
     * detects the {@code -javaagent:pyroscope.jar} the operator already injected into the JVM
     * and surfaces the result in {@code /actuator/pulse}; the profile-link attributes work
     * regardless of whether the agent is running, since they only describe how to find the
     * profile if one exists.
     */
    public record Profiling(
            @DefaultValue("true") boolean enabled, @Nullable String pyroscopeUrl) {}

    /**
     * Dependency health map — turns every outbound HTTP/Kafka call into a per-dependency RED
     * (rate / errors / duration) signal so an operator can answer "which downstream is hurting
     * me?" without opening N dashboards.
     *
     * <p>Each outbound call is tagged with a logical {@code pulse.dependency} derived from the
     * {@link #map() host&nbsp;→&nbsp;name} table (or, when the host is unknown, the value of
     * {@link #defaultName()}). Pulse emits:
     *
     * <ul>
     *   <li>{@code pulse.dependency.requests{dep, method, status, outcome}} — counter
     *   <li>{@code pulse.dependency.latency{dep, method, outcome}} — timer with the standard SLO
     *       buckets so p50/p95/p99 are computable in Prometheus
     *   <li>{@code pulse.request.fan_out} — distribution of outbound calls per inbound request
     *   <li>{@code pulse.request.distinct_dependencies} — distribution of distinct downstreams
     *       per inbound request (helps spot fan-out width regressions before they cascade)
     * </ul>
     *
     * <p>Cardinality is bounded by your dependency count: if you call {@code payment-service} from
     * 50 endpoints, the {@code dep} tag still has only one value. The defaults survive
     * 10k-pod deployments without operator intervention.
     *
     * <p>{@link #fanOutWarnThreshold()} controls when {@code pulse.request.fan_out_high} fires —
     * the per-request guardrail that lets you alert "endpoint X went from 5 calls to 50". A
     * threshold of {@code 20} catches the classic loop-fetching anti-pattern at the call layer
     * without firing for legitimate aggregations.
     */
    public record Dependencies(
            @DefaultValue("true") boolean enabled,
            @DefaultValue Map<String, String> map,
            @DefaultValue("unknown") String defaultName,
            @DefaultValue("20") int fanOutWarnThreshold,
            @DefaultValue PulseRequestMatcherProperties enabledWhen,
            @DefaultValue Health health) {

        /**
         * Topology-aware health configuration.
         * {@link io.github.arun0009.pulse.dependencies.DependencyHealthIndicator} reads the
         * existing dependency RED metrics ({@code pulse.dependency.requests},
         * {@code pulse.dependency.latency}) and reports {@code DEGRADED} when a dependency
         * named in {@link #critical()} crosses {@link #errorRateThreshold()} over the trailing
         * window, or when its {@code SERVER_ERROR} count is non-zero in the same window.
         *
         * <p>This is inference, not certainty — the indicator never calls the downstream's
         * health endpoint, so it costs zero extra requests and cannot create circular
         * dependencies between services. The trade-off is that brand-new processes report
         * {@code UP} for critical dependencies they haven't called yet; that is intentional.
         *
         * <p>{@link #down()} controls whether a degraded critical dependency reports
         * {@code OUT_OF_SERVICE} (the standard Kubernetes-friendly readiness signal) or stays
         * at {@code DEGRADED}. Default is {@code false} (DEGRADED only) so installing Pulse
         * never flips an existing service's readiness contract.
         */
        public record Health(
                @DefaultValue("true") boolean enabled,
                @DefaultValue({}) List<String> critical,
                @DefaultValue("0.05") double errorRateThreshold,
                @DefaultValue("false") boolean down) {}
    }

    /**
     * Multi-tenant context — extracts the tenant id from the inbound request, propagates it on
     * MDC + outbound HTTP/Kafka headers, and (optionally) tags Micrometer meters with it.
     *
     * <p>Three built-in extractors ship with Pulse, each opt-in via its own {@code enabled}
     * flag. The header extractor is on by default. Applications add their own by declaring a
     * {@code @Bean TenantExtractor} — Spring's {@code @Order} controls the resolution order.
     *
     * <p>Resolution priority (first non-empty wins):
     *
     * <ol>
     *   <li>{@code pulse.tenant.id} system property — for {@code @SpringBootTest} and dev.
     *   <li>The highest-priority extractor whose {@code extract()} returns non-empty.
     *   <li>{@link #unknownValue()} — written to MDC and used as the metric tag.
     * </ol>
     *
     * <p>Cardinality is the killer concern with tenant tags. {@link #maxTagCardinality()} sits on
     * top of the global {@link Cardinality#maxTagValuesPerMeter()} ceiling so a multi-tenant SaaS
     * can keep the global cap at 1000 (for paths, methods, statuses) while keeping the tenant
     * tag at a much tighter 100. Excess tenants are bucketed to {@link #overflowValue()}.
     *
     * <p>Propagation defaults: tenant id always flows on MDC, OTel baggage (key
     * {@code pulse.tenant.id}, mirrored by {@link io.github.arun0009.pulse.tenant.TenantContextFilter}),
     * outbound HTTP/Kafka headers (via {@link io.github.arun0009.pulse.propagation.HeaderPropagation}),
     * and the {@code pulse.events} wide-event counter. Metric tagging on {@code http.server.requests}, {@code
     * pulse.dependency.*}, etc. is opt-in via {@link #tagMeters()} — operators add the meter
     * names they want tenant attribution on, and the cardinality cap protects them.
     */
    public record Tenant(
            @DefaultValue("true") boolean enabled,
            @DefaultValue Header header,
            @DefaultValue Jwt jwt,
            @DefaultValue Subdomain subdomain,
            @DefaultValue("100") int maxTagCardinality,
            @DefaultValue("__overflow__") String overflowValue,
            @DefaultValue("unknown") String unknownValue,
            @DefaultValue({}) List<String> tagMeters) {

        /**
         * Header-based extraction. Default: read {@code Pulse-Tenant-Id} (RFC 6648 — no
         * {@code X-} prefix).
         */
        public record Header(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("Pulse-Tenant-Id") String name) {}

        /**
         * JWT-claim extraction. Reads the {@code Authorization: Bearer ...} header, parses the
         * payload as JSON without verifying the signature, and reads {@link #claim()}. Pulse does
         * not perform signature verification — that's Spring Security's job. The claim is treated
         * as advisory metadata once Security has authenticated the request.
         */
        public record Jwt(
                @DefaultValue("false") boolean enabled,
                @DefaultValue("tenant_id") String claim) {}

        /**
         * Subdomain extraction. Splits {@code Host} on dots and returns the segment at
         * {@link #index()} (default {@code 0} — the leftmost label). For
         * {@code acme.app.example.com} with index 0, the tenant resolves to {@code acme}.
         */
        public record Subdomain(
                @DefaultValue("false") boolean enabled,
                @DefaultValue("0") int index) {}
    }

    /**
     * Retry amplification detection — propagates a hop counter through the call chain so
     * Pulse can detect "service A retries 3x → B retries 3x → C gets 9x its normal traffic"
     * cascades <em>before</em> they become an outage.
     *
     * <p>The depth is carried over the wire as the {@link #headerName()} header (default
     * {@code Pulse-Retry-Depth}, RFC 6648 — no {@code X-} prefix) and on MDC under the
     * {@code retryDepth} key, so every log line participating in an amplified chain is tagged.
     * Each Resilience4j retry attempt observed by Pulse increments the local depth by 1;
     * outbound HTTP/Kafka propagation re-emits the current depth so the next hop inherits it.
     *
     * <p>When the inbound depth exceeds {@link #amplificationThreshold()} (default {@code 3}),
     * Pulse increments {@code pulse.retry.amplification{endpoint}} (counter; Prometheus exposition
     * adds {@code _total}), adds a span event {@code pulse.retry.amplification}, and logs at
     * WARN. The combination of timeout-budget propagation + retry depth gives operators the two
     * leading indicators of cascading failure in one starter.
     */
    public record Retry(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("Pulse-Retry-Depth") String headerName,
            @DefaultValue("3") int amplificationThreshold) {}

    /**
     * Request criticality propagation. Pulse extracts the priority from the configured inbound
     * header (default {@code Pulse-Priority}, RFC 6648 — no {@code X-} prefix), normalizes it
     * against the five-tier vocabulary {@code critical, high, normal, low, background}, mirrors
     * it onto {@link io.github.arun0009.pulse.core.ContextKeys#PRIORITY MDC} and OTel baggage
     * (key {@code pulse.priority}, set by
     * {@link io.github.arun0009.pulse.priority.RequestPriorityFilter}), and re-emits it on every
     * outbound HTTP/Kafka call.
     *
     * <p>{@link #tagMeters()} is the opt-in metric-tagging surface: list the meter names you
     * want stamped with {@code priority=...} (e.g. {@code http.server.requests} for per-priority
     * SLOs). The tag values come from the bounded five-tier vocabulary, so cardinality is
     * predictable — no firewall coupling is needed.
     *
     * <p>{@link #warnOnCriticalTimeoutExhaustion()} elevates the WARN line emitted when the
     * timeout budget is exhausted to ERROR if the request was tagged {@code critical} — a
     * distinct signal that something operationally important was dropped.
     */
    public record Priority(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("Pulse-Priority") String headerName,
            @DefaultValue("normal") String defaultPriority,
            @DefaultValue("true") boolean warnOnCriticalTimeoutExhaustion,
            @DefaultValue({}) List<String> tagMeters) {}

    /**
     * Container memory observability — fills the JVM-vs-cgroup blind spot that bites every team
     * that has ever Googled "OOMKilled but heap looks fine."
     *
     * <p>Micrometer's {@code jvm.memory.*} reports heap and non-heap, but the kernel kills your
     * pod based on RSS measured against the cgroup limit, which includes off-heap allocations
     * (direct buffers, native libraries, JIT, metaspace, threads). Pulse reads
     * {@code memory.current}/{@code memory.max} (cgroup v2) or
     * {@code memory.usage_in_bytes}/{@code memory.limit_in_bytes} (cgroup v1) and exposes:
     *
     * <ul>
     *   <li>{@code pulse.container.memory.used} (base unit: bytes) — current RSS as the kernel sees it.
     *   <li>{@code pulse.container.memory.limit} (base unit: bytes) — the cgroup's hard memory limit.
     *   <li>{@code pulse.container.memory.headroom_ratio} — {@code 1 - used/limit}, the value
     *       your runbook actually wants.
     *   <li>{@code pulse.container.memory.oom_kills} — counter sourced from
     *       {@code memory.events}/{@code memory.oom_control}; a non-zero rate means a sibling
     *       cgroup got killed under the same controller.
     * </ul>
     *
     * <p>Resolution is best-effort: on macOS / Windows / non-cgroup hosts the reader returns
     * empty values and Pulse silently registers no gauges, so the same starter works on a
     * developer laptop and in a Kubernetes pod. {@link #headroomCriticalRatio()} is the value
     * the optional health indicator uses to switch from {@code UP} to {@code OUT_OF_SERVICE}.
     */
    public record ContainerMemory(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("true") boolean healthIndicatorEnabled,
            @DefaultValue("0.10") double headroomCriticalRatio,
            @DefaultValue("/sys/fs/cgroup") String cgroupRoot) {}

    /**
     * OpenFeature integration. When the {@code dev.openfeature:sdk} is on the classpath, Pulse
     * registers a {@link io.github.arun0009.pulse.openfeature.PulseOpenFeatureMdcHook} that
     * threads flag values onto MDC and stamps OTel-semconv {@code feature_flag} span events. If
     * the optional {@code dev.openfeature.contrib.hooks:otel} hook is also present, Pulse
     * registers it reflectively so consumers do not have to wire it themselves.
     *
     * <p>Setting {@code pulse.open-feature.enabled=false} suppresses both registrations.
     */
    public record OpenFeature(@DefaultValue("true") boolean enabled) {}

    /**
     * Cache observability — currently scoped to Caffeine via Spring's {@code CaffeineCacheManager}.
     * When enabled (default), Pulse binds every {@code CaffeineCacheManager} bean to Micrometer
     * so {@code cache.gets}, {@code cache.puts}, {@code cache.evictions}, and
     * {@code cache.hit_ratio} land on the registry with no extra configuration. Pulse never
     * mutates the operator's Caffeine spec; if {@code recordStats()} is missing, the bind happens
     * anyway (meters report zero) and a one-time WARN per manager bean is logged.
     *
     * <p>Setting {@code pulse.cache.caffeine.enabled=false} disables the binding.
     */
    public record Cache(@DefaultValue Caffeine caffeine) {

        public record Caffeine(@DefaultValue("true") boolean enabled) {}
    }
}
