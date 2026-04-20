package io.github.arun0009.pulse.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;

import java.util.Optional;

/**
 * Reads the tenant id from a label of the {@code Host} header. Useful for SaaS apps where the
 * tenant identifier is part of the public URL — {@code acme.app.example.com} resolves to
 * {@code acme} (with {@code index=0}, the leftmost label).
 *
 * <p>The host's port is stripped, the value is split on {@code .}, and the segment at
 * {@code index} is returned. If the index is out of bounds (host has fewer labels than
 * expected) the extractor returns empty rather than guessing.
 *
 * <p>This extractor is opt-in (default off) because most service-to-service traffic does not
 * carry a tenant-encoding hostname — it carries an explicit header.
 */
public final class SubdomainTenantExtractor implements TenantExtractor, Ordered {

    /** Last in the chain — least authoritative signal. */
    public static final int ORDER = 300;

    private final int index;

    public SubdomainTenantExtractor(int index) {
        this.index = index;
    }

    @Override
    public Optional<String> extract(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null) host = request.getServerName();
        if (host == null || host.isEmpty()) return Optional.empty();
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        String[] labels = host.split("\\.", -1);
        if (index < 0 || index >= labels.length) return Optional.empty();
        String label = labels[index];
        return label.isEmpty() ? Optional.empty() : Optional.of(label);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
