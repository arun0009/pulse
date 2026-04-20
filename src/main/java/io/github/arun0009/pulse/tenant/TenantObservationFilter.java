package io.github.arun0009.pulse.tenant;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adds the {@code tenant} tag to every {@link io.micrometer.observation.Observation} whose name
 * starts with one of the operator-configured prefixes in
 * {@link TenantProperties#tagMeters()}.
 *
 * <p>Why an {@link ObservationFilter} rather than a Micrometer {@code MeterFilter}: meter
 * filters can only modify the {@code Meter.Id} once (at meter registration), so they cannot
 * inject a per-recording tag whose value changes per request. Observation filters run on every
 * observation start and have access to the live thread-local {@link TenantContext}.
 *
 * <p>Spring Boot's auto-instrumented {@code http.server.requests}, {@code http.client.requests},
 * Pulse's {@code pulse.dependency.requests}, and any user-emitted observations all flow
 * through this filter when their name matches a configured prefix. The actual cardinality
 * cap is enforced separately by {@link TenantTagCardinalityFilter}.
 */
public final class TenantObservationFilter implements ObservationFilter {

    private final Set<String> exactPrefixes;
    private final List<String> prefixList;

    public TenantObservationFilter(List<String> tagMeters) {
        this.exactPrefixes = new HashSet<>(tagMeters);
        this.prefixList = List.copyOf(tagMeters);
    }

    @Override
    public Observation.Context map(Observation.Context context) {
        String name = context.getName();
        if (name == null || !matches(name)) return context;
        TenantContext.current().ifPresent(t -> context.addLowCardinalityKeyValue(KeyValue.of("tenant", t)));
        return context;
    }

    private boolean matches(String name) {
        if (exactPrefixes.contains(name)) return true;
        for (String prefix : prefixList) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
