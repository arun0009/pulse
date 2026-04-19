package io.github.arun0009.pulse.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;

import java.util.Optional;

/**
 * Reads the tenant id from a single request header (default {@code Pulse-Tenant-Id}). The standard
 * choice for service-to-service traffic where the platform proxy or auth gateway has already
 * resolved the tenant.
 *
 * <p>Empty / blank header values are treated as absent so an upstream that always sets the
 * header but sometimes leaves it empty does not drown the meter in {@code tenant=""} entries.
 */
public final class HeaderTenantExtractor implements TenantExtractor, Ordered {

    /** Runs first by default — explicit headers are the highest-confidence signal. */
    public static final int ORDER = 100;

    private final String headerName;

    public HeaderTenantExtractor(String headerName) {
        this.headerName = headerName;
    }

    @Override
    public Optional<String> extract(HttpServletRequest request) {
        String value = request.getHeader(headerName);
        if (value == null) return Optional.empty();
        String trimmed = value.trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
