package io.github.arun0009.pulse.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;

import java.util.List;

/**
 * Default histogram + percentile + SLO-bucket configuration for the standard observability meters
 * Spring Boot ships with ({@code http.server.requests}, {@code jdbc.query}, {@code
 * spring.kafka.listener}). Without this every meter reports only {@code count/sum/max} and you
 * cannot answer "what is my P99?" from the metrics backend alone.
 */
public final class HistogramMeterFilter implements MeterFilter {

    private final HistogramsProperties config;

    public HistogramMeterFilter(HistogramsProperties config) {
        this.config = config;
    }

    @Override
    public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig source) {
        if (!config.enabled() || !matches(id.getName())) return source;

        double[] buckets = config.sloBuckets().stream()
                .mapToDouble(d -> (double) d.toNanos())
                .toArray();
        return DistributionStatisticConfig.builder()
                .percentilesHistogram(true)
                .percentiles(0.5, 0.9, 0.95, 0.99)
                .serviceLevelObjectives(buckets)
                .build()
                .merge(source);
    }

    private boolean matches(String name) {
        List<String> prefixes = config.meterPrefixes();
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
