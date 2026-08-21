package io.github.arun0009.pulse.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Cache observability — currently scoped to Caffeine via Spring's
 * {@code CaffeineCacheManager}. Opt-in via {@code pulse.cache.caffeine.enabled=true};
 * Spring Boot and Micrometer already bind Caffeine when {@code recordStats()} is set.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.cache")
public record CacheProperties(@DefaultValue Caffeine caffeine) {
    public record Caffeine(@DefaultValue("false") boolean enabled) {}
}
