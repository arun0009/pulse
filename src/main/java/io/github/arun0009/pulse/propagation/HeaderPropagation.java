package io.github.arun0009.pulse.propagation;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.github.arun0009.pulse.core.ContextKeys;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the (header, mdc-key) pairs that Pulse propagates from the current MDC context to
 * outbound HTTP/Kafka requests. Centralized so RestTemplate, WebClient, OkHttp, and Kafka
 * interceptors all agree on the wire format.
 */
public final class HeaderPropagation {

    private HeaderPropagation() {}

    public static Map<String, String> headerToMdcKey(PulseProperties.Context config) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(config.requestIdHeader(), ContextKeys.REQUEST_ID);
        map.put(config.correlationIdHeader(), ContextKeys.CORRELATION_ID);
        map.put(config.userIdHeader(), ContextKeys.USER_ID);
        map.put(config.tenantIdHeader(), ContextKeys.TENANT_ID);
        // Side-effect deduplication: mirror an inbound Idempotency-Key onto outbound calls so
        // downstream services (or compatible libraries like io.github.arun0009/idempotent) can
        // reject duplicates introduced by retries or fan-out.
        map.put(config.idempotencyKeyHeader(), ContextKeys.IDEMPOTENCY_KEY);
        return map;
    }

    /**
     * Same as {@link #headerToMdcKey(PulseProperties.Context)} but additionally registers the
     * retry-amplification header so {@code retryDepth} on MDC is mirrored downstream. The retry
     * config is opt-out via {@code pulse.retry.enabled=false}, so transports that always pass it
     * here remain backwards-compatible.
     */
    public static Map<String, String> headerToMdcKey(PulseProperties.Context config, PulseProperties.Retry retry) {
        Map<String, String> map = headerToMdcKey(config);
        if (retry != null && retry.enabled()) {
            map.put(retry.headerName(), ContextKeys.RETRY_DEPTH);
        }
        return map;
    }

    /**
     * Same as {@link #headerToMdcKey(PulseProperties.Context, PulseProperties.Retry)} plus the
     * request-priority header (default {@code Pulse-Priority}). Priority propagation is opt-out
     * via {@code pulse.priority.enabled=false}.
     */
    public static Map<String, String> headerToMdcKey(
            PulseProperties.Context config, PulseProperties.Retry retry, PulseProperties.Priority priority) {
        Map<String, String> map = headerToMdcKey(config, retry);
        if (priority != null && priority.enabled()) {
            map.put(priority.headerName(), ContextKeys.PRIORITY);
        }
        return map;
    }
}
