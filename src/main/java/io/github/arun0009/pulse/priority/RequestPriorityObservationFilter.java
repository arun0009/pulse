package io.github.arun0009.pulse.priority;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stamps the {@code priority} tag on every {@link Observation} whose name matches an
 * operator-configured entry in
 * {@link PriorityProperties#tagMeters()}.
 *
 * <p>The tag value is the {@link RequestPriority#wireValue()} of the current request. Because
 * the vocabulary is bounded to five tiers, no cardinality firewall coupling is required — the
 * worst case is five extra series per matched meter per pre-existing tag combination.
 *
 * <p>Operators typically tag {@code http.server.requests} and {@code pulse.dependency.requests}
 * to enable per-priority SLO burn rates ("99.9% availability for {@code critical}, 99% for
 * {@code normal}"). Default {@code tagMeters} is empty so installing Pulse never adds a metric
 * tag without explicit opt-in.
 */
public final class RequestPriorityObservationFilter implements ObservationFilter {

    private final Set<String> exactNames;
    private final List<String> prefixList;

    public RequestPriorityObservationFilter(List<String> tagMeters) {
        this.exactNames = new HashSet<>(tagMeters);
        this.prefixList = List.copyOf(tagMeters);
    }

    @Override
    public Observation.Context map(Observation.Context context) {
        String name = context.getName();
        if (name == null || !matches(name)) return context;
        RequestPriority.current()
                .ifPresent(p -> context.addLowCardinalityKeyValue(KeyValue.of("priority", p.wireValue())));
        return context;
    }

    private boolean matches(String name) {
        if (exactNames.contains(name)) return true;
        for (String prefix : prefixList) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
