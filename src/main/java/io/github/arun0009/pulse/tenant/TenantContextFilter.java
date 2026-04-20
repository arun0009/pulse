package io.github.arun0009.pulse.tenant;

import io.github.arun0009.pulse.core.ContextKeys;
import io.github.arun0009.pulse.core.PulseRequestContextFilter;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the tenant for the current request by walking the configured
 * {@link TenantExtractor} chain (lowest order first), then writes it to:
 *
 * <ul>
 *   <li>{@link MDC} as {@code tenantId} — every log line carries it.
 *   <li>{@link TenantContext} — user code and Pulse subsystems read via {@code current()}.
 * </ul>
 *
 * <p>Outbound propagation re-uses the existing
 * {@link io.github.arun0009.pulse.propagation.HeaderPropagation} machinery: the
 * {@code tenantId} MDC key is already mirrored onto outbound HTTP and Kafka requests using
 * the configured tenant header (default {@code Pulse-Tenant-Id}), so writing the resolved
 * tenant to MDC is the only step needed for it to flow downstream.
 *
 * <p>Runs after {@link PulseRequestContextFilter} so MDC is already populated; this filter
 * <em>overwrites</em> {@code tenantId} when the extractor chain produces a value, then
 * restores the previous value on exit. A no-extractor configuration leaves MDC untouched,
 * so the simple "header → MDC" path still works for apps that don't need extractor logic.
 *
 * <p>Resolution priority:
 * <ol>
 *   <li>{@code pulse.tenant.id} system property (test / dev override).
 *   <li>First non-empty extractor result, lowest order first.
 *   <li>{@link TenantProperties#unknownValue()} (default {@code "unknown"}) — written to
 *       MDC and {@code TenantContext} so downstream signals are uniformly tagged rather than
 *       silently null.
 * </ol>
 */
public final class TenantContextFilter extends OncePerRequestFilter implements Ordered {

    /**
     * Sits between RequestPriority (+20) and RetryDepth (+40), well before TraceGuard (+50).
     * Running after Priority means tenant-extractor implementations that route on priority
     * (e.g. shadow tenant for low-pri traffic) see a populated {@code priority} MDC key;
     * running before RetryDepth means the retry-amplification WARN carries the tenant id.
     */
    public static final int ORDER = PulseRequestContextFilter.ORDER + 30;

    private static final String SYSTEM_PROPERTY = "pulse.tenant.id";

    private final List<TenantExtractor> extractors;
    private final String unknownValue;

    public TenantContextFilter(List<TenantExtractor> extractors, TenantProperties config) {
        this.extractors = extractors;
        this.unknownValue = config.unknownValue();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        @Nullable String previousMdc = MDC.get(ContextKeys.TENANT_ID);
        @Nullable String previousContext = TenantContext.current().orElse(null);
        String tenant = resolve(request);
        Baggage updated = Baggage.current().toBuilder()
                .put(ContextKeys.TENANT_BAGGAGE_KEY, tenant)
                .build();
        try (Scope ignored = updated.storeInContext(Context.current()).makeCurrent()) {
            MDC.put(ContextKeys.TENANT_ID, tenant);
            TenantContext.set(tenant);
            chain.doFilter(request, response);
        } finally {
            if (previousMdc == null) {
                MDC.remove(ContextKeys.TENANT_ID);
            } else {
                MDC.put(ContextKeys.TENANT_ID, previousMdc);
            }
            TenantContext.set(previousContext);
        }
    }

    private String resolve(HttpServletRequest request) {
        String fromSystemProperty = System.getProperty(SYSTEM_PROPERTY);
        if (fromSystemProperty != null && !fromSystemProperty.isBlank()) {
            return fromSystemProperty.trim();
        }
        for (TenantExtractor extractor : extractors) {
            Optional<String> resolved = extractor.extract(request);
            if (resolved.isPresent() && !resolved.get().isBlank()) {
                return resolved.get().trim();
            }
        }
        return unknownValue;
    }
}
