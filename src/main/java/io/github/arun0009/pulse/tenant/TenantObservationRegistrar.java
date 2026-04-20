package io.github.arun0009.pulse.tenant;

import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import org.jspecify.annotations.Nullable;

/**
 * Registers {@link TenantObservationFilter} on the application's
 * {@link ObservationRegistry} when one is available. Pulled out of
 * {@link io.github.arun0009.pulse.tenant.internal.PulseTenantConfiguration} so the registration runs as a {@link PostConstruct} after
 * Spring Boot's {@code ObservationAutoConfiguration} has finished initializing the registry.
 */
public final class TenantObservationRegistrar {

    private final @Nullable ObservationRegistry registry;
    private final TenantObservationFilter filter;

    public TenantObservationRegistrar(@Nullable ObservationRegistry registry, TenantObservationFilter filter) {
        this.registry = registry;
        this.filter = filter;
    }

    @PostConstruct
    public void register() {
        if (registry != null) {
            registry.observationConfig().observationFilter(filter);
        }
    }
}
