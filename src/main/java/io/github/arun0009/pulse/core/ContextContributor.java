package io.github.arun0009.pulse.core;

import jakarta.servlet.http.HttpServletRequest;

/**
 * SPI for adding service-specific MDC keys to every inbound request.
 *
 * <p>Register a Spring bean implementing this interface and Pulse will invoke it after standard
 * keys (traceId, requestId, userId, tenantId, etc.) have been set. Do not call {@code MDC.clear()}
 * — Pulse owns the lifecycle.
 *
 * <pre>
 * &#064;Component
 * class RegionContributor implements ContextContributor {
 *     public void contribute(HttpServletRequest request) {
 *         var region = request.getHeader("X-Region");
 *         if (region != null) MDC.put("region", region);
 *     }
 * }
 * </pre>
 */
@FunctionalInterface
public interface ContextContributor {

    void contribute(HttpServletRequest request);
}
