package io.github.arun0009.pulse.core;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * MDC enrichment from the inbound HTTP request.
 *
 * <p>Maps inbound HTTP headers to MDC keys so every log line and span carries
 * request/correlation/user/tenant identity. Customize header names per your edge convention.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.context")
public record ContextProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("X-Request-ID") @NotBlank String requestIdHeader,
        @DefaultValue("X-Correlation-ID") @NotBlank String correlationIdHeader,
        @DefaultValue("X-User-ID") @NotBlank String userIdHeader,
        @DefaultValue("Pulse-Tenant-Id") @NotBlank String tenantIdHeader,
        @DefaultValue("Idempotency-Key") @NotBlank String idempotencyKeyHeader,
        @DefaultValue({}) List<String> additionalHeaders) {}
