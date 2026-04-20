package io.github.arun0009.pulse.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/** Histogram + percentile defaults for Spring Boot's standard meters. */
@Validated
@ConfigurationProperties(prefix = "pulse.histograms")
public record HistogramsProperties(
        @DefaultValue("true") boolean enabled,

        @DefaultValue({"http.server.requests", "jdbc.query", "spring.kafka.listener"})
        List<String> meterPrefixes,

        @DefaultValue({"50ms", "100ms", "250ms", "500ms", "1s", "5s"})
        List<Duration> sloBuckets) {}
