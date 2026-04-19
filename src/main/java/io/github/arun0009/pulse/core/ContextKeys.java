package io.github.arun0009.pulse.core;

/**
 * Canonical MDC key names emitted by Pulse.
 *
 * <p>All Pulse-managed log fields are namespaced here so that downstream consumers (JSON layout,
 * exception handler, dependency recorder) reference one source of truth instead of stringly-typed keys.
 *
 * <p>{@code traceId} and {@code spanId} are populated by the OpenTelemetry log appender /
 * Micrometer Tracing — never by Pulse code directly. They are listed here for reference only.
 */
public final class ContextKeys {

    private ContextKeys() {}

    public static final String SERVICE_NAME = "service";
    public static final String ENVIRONMENT = "env";

    public static final String REQUEST_ID = "requestId";
    public static final String CORRELATION_ID = "correlationId";
    public static final String USER_ID = "userId";
    public static final String TENANT_ID = "tenantId";

    /**
     * Optional client-supplied key used to deduplicate side-effecting requests. When present on the
     * inbound request, Pulse mirrors it to MDC and re-emits it on outbound HTTP and Kafka
     * propagation so the deduplication boundary survives every hop.
     */
    public static final String IDEMPOTENCY_KEY = "idempotencyKey";

    /**
     * Cross-service retry-amplification counter. Seeded from the inbound
     * {@code Pulse-Retry-Depth} header (or whatever {@code pulse.retry.header-name} resolves
     * to), bumped by every Resilience4j retry attempt observed by Pulse, and re-emitted on
     * outbound HTTP/Kafka calls so the next hop inherits it. When the inbound depth crosses
     * {@code pulse.retry.amplification-threshold}, Pulse emits an amplification metric, span
     * event, and WARN log.
     */
    public static final String RETRY_DEPTH = "retryDepth";

    /**
     * Request criticality / load-shedding hint. Carried over the wire as the
     * {@code Pulse-Priority} header (default; configurable via
     * {@code pulse.priority.header-name}), mirrored to MDC and OTel baggage so every log line
     * and downstream call inherits it. The vocabulary is the five OpenTelemetry-aligned tiers
     * {@code critical}, {@code high}, {@code normal}, {@code low}, {@code background}; an
     * unknown value is normalized to {@code normal} on read. Application code consults
     * {@link io.github.arun0009.pulse.priority.RequestPriority#current()} to make load-shedding
     * decisions; unhandled-error and timeout-budget paths emit at {@code WARN} when a request
     * with priority {@code critical} blows its budget.
     */
    public static final String PRIORITY = "priority";

    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";

    public static final String RESPONSE_TRACE_HEADER = "X-Trace-ID";
}
