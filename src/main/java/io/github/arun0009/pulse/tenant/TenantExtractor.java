package io.github.arun0009.pulse.tenant;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * SPI for resolving the tenant identity from an inbound HTTP request.
 *
 * <p>Pulse ships three built-in implementations ({@link HeaderTenantExtractor},
 * {@link JwtClaimTenantExtractor}, {@link SubdomainTenantExtractor}), each opt-in via its own
 * {@code pulse.tenant.*.enabled} property. Applications register their own extractor by
 * declaring a {@code @Bean TenantExtractor} — Spring's {@code @Order} annotation controls the
 * resolution order (lowest order runs first).
 *
 * <p>Implementations should be cheap (allocations matter on the request hot path) and must
 * return {@link Optional#empty()} when they do not have an authoritative answer rather than
 * a placeholder string. The first non-empty result wins.
 *
 * <p>Pulse does not invoke extractors in async / reactive paths — only on the inbound servlet
 * request thread. Tenant identity then propagates through the existing MDC + outbound header
 * chain to all downstream calls.
 */
@FunctionalInterface
public interface TenantExtractor {

    Optional<String> extract(HttpServletRequest request);
}
