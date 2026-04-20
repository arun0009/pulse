package io.github.arun0009.pulse.tenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Multi-tenant context — extracts the tenant id from the inbound request, propagates it on
 * MDC + outbound HTTP/Kafka headers, and (optionally) tags Micrometer meters with it.
 *
 * <p>Cardinality is the killer concern with tenant tags. {@link #maxTagCardinality()} sits on
 * top of the global {@code pulse.cardinality.max-tag-values-per-meter} ceiling so a
 * multi-tenant SaaS can keep the global cap at 1000 while keeping the tenant tag at a
 * much tighter 100.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.tenant")
public record TenantProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue @Valid Header header,
        @DefaultValue @Valid Jwt jwt,
        @DefaultValue @Valid Subdomain subdomain,
        @DefaultValue("100") @Positive int maxTagCardinality,
        @DefaultValue("__overflow__") @NotBlank String overflowValue,
        @DefaultValue("unknown") @NotBlank String unknownValue,
        @DefaultValue({}) List<String> tagMeters) {

    /** Header-based extraction. Default: read {@code Pulse-Tenant-Id}. */
    public record Header(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("Pulse-Tenant-Id") @NotBlank String name) {}

    /** JWT-claim extraction. Reads the {@code Authorization: Bearer …} header unverified. */
    public record Jwt(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("tenant_id") @NotBlank String claim) {}

    /** Subdomain extraction. Splits {@code Host} on dots and returns the segment at {@link #index()}. */
    public record Subdomain(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("0") @PositiveOrZero int index) {}
}
