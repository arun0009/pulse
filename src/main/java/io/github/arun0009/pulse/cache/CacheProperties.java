package io.github.arun0009.pulse.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Cache observability — currently scoped to Caffeine via Spring's
 * {@code CaffeineCacheManager}. When enabled (default), Pulse binds every
 * {@code CaffeineCacheManager} bean to Micrometer.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.cache")
public record CacheProperties(@DefaultValue Caffeine caffeine) {
    public record Caffeine(@DefaultValue("true") boolean enabled) {}
}
