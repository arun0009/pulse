package io.github.arun0009.pulse.tenant;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Static accessor for the current request's tenant id. Mirrors the pattern used by
 * {@link io.github.arun0009.pulse.guardrails.TimeoutBudget#current()} so user code reads the
 * tenant the same way it reads the timeout budget — without knowing about MDC, baggage, or
 * filters.
 *
 * <p>Backed by a {@link ThreadLocal}; populated by {@link TenantContextFilter} on the inbound
 * thread and copied across thread hops by {@link io.github.arun0009.pulse.async.PulseTaskDecorator}
 * (which uses MDC, where the tenant is mirrored).
 *
 * <p>Async / reactive code that runs off the original request thread can either rely on the
 * MDC propagation (the tenant survives because Pulse copies MDC through the decorator) or read
 * it explicitly from MDC via {@code MDC.get("tenantId")} — both paths agree by construction.
 */
public final class TenantContext {

    private TenantContext() {}

    private static final ThreadLocal<@Nullable String> CURRENT = new ThreadLocal<>();

    /** Sets the tenant for the current thread. Pass {@code null} to clear. */
    public static void set(@Nullable String tenantId) {
        if (tenantId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenantId);
        }
    }

    /** Clears the thread-local. Equivalent to {@code set(null)}. */
    public static void clear() {
        CURRENT.remove();
    }

    /** The tenant for this thread, if any has been set. */
    public static Optional<String> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
