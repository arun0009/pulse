package io.github.arun0009.pulse.fleet;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * Registers a constant {@code 1} gauge tagged with the resolved {@code pulse.*} configuration
 * hash so a fleet-wide drift detector can be expressed as
 *
 * <pre>
 * count by (application) (count by (application, hash) (pulse_config_hash)) > 1
 * </pre>
 *
 * <p>The hash is computed once at startup; the gauge holder is final, so this metric carries no
 * runtime cost beyond the standard meter scrape. Cardinality is intrinsically bounded by the
 * number of distinct configurations across the fleet (typically 1 per service-version pair, with
 * brief 2-state windows during rolling deploys).
 */
public final class ConfigHashGauge {

    static final String METRIC = "pulse.config.hash";

    private final MeterRegistry registry;
    private final String hash;

    public ConfigHashGauge(MeterRegistry registry, String hash) {
        this.registry = registry;
        this.hash = hash;
    }

    public String hash() {
        return hash;
    }

    public void register() {
        Gauge.builder(METRIC, () -> 1.0)
                .description("Constant 1 gauge tagged with the resolved pulse.* config hash for fleet drift detection")
                .tags(Tags.of("hash", hash))
                .register(registry);
    }
}
