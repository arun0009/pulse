/**
 * Multi-tenant context — extracts the tenant id from the inbound request, propagates it on
 * MDC and outbound HTTP/Kafka headers, and (opt-in) tags Micrometer meters with it.
 *
 * <p>Three built-in {@link io.github.arun0009.pulse.tenant.TenantExtractor} implementations
 * ship with Pulse: {@link io.github.arun0009.pulse.tenant.HeaderTenantExtractor},
 * {@link io.github.arun0009.pulse.tenant.JwtClaimTenantExtractor},
 * {@link io.github.arun0009.pulse.tenant.SubdomainTenantExtractor}. Applications register
 * their own by declaring a {@code @Bean TenantExtractor} — Spring's {@code @Order} controls
 * resolution priority.
 *
 * <p>Cardinality protection lives in
 * {@link io.github.arun0009.pulse.tenant.TenantTagCardinalityFilter}, which caps the
 * {@code tenant} tag at {@code pulse.tenant.max-tag-cardinality} (default 100) independently
 * of the global cardinality firewall.
 */
@NullMarked
package io.github.arun0009.pulse.tenant;

import org.jspecify.annotations.NullMarked;
