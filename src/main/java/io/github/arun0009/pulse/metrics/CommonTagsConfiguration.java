package io.github.arun0009.pulse.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tags every meter with {@code application} and {@code env} so dashboards stay filterable across
 * services without each team having to remember to add them by hand. Honors {@code
 * spring.application.name} and {@code app.env}.
 */
@Configuration(proxyBeanMethods = false)
public class CommonTagsConfiguration {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> pulseCommonTags(
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${app.env:unknown-env}") String environment) {
        return registry -> registry.config()
                .commonTags(
                        "application", serviceName,
                        "env", environment);
    }
}
