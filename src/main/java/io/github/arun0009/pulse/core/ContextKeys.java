package io.github.arun0009.pulse.core;

/**
 * Canonical MDC key names emitted by Pulse.
 *
 * <p>All Pulse-managed log fields are namespaced here so that downstream consumers (JSON layout,
 * exception handler, audit logger) reference one source of truth instead of stringly-typed keys.
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

    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";

    public static final String RESPONSE_TRACE_HEADER = "X-Trace-ID";
}
