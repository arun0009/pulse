package io.github.arun0009.pulse.cache;

import io.github.arun0009.pulse.cache.PulseCaffeineConfiguration.PulseCaffeineCacheCustomizer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import static org.assertj.core.api.Assertions.assertThat;

class PulseCaffeineConfigurationTest {

    @Test
    void enables_record_stats_on_caffeine_cache_manager() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PulseCaffeineCacheCustomizer customizer = new PulseCaffeineCacheCustomizer(registry);
        CaffeineCacheManager manager = new CaffeineCacheManager("orders");

        customizer.postProcessAfterInitialization(manager, "cacheManager");

        CaffeineCache cache = (CaffeineCache) manager.getCache("orders");
        assertThat(cache).isNotNull();
        cache.put("k1", "v1");
        cache.get("k1");
        cache.get("missing");

        assertThat(cache.getNativeCache().stats().hitCount()).isEqualTo(1);
        assertThat(cache.getNativeCache().stats().missCount()).isEqualTo(1);
    }

    @Test
    void binds_existing_caches_to_micrometer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PulseCaffeineCacheCustomizer customizer = new PulseCaffeineCacheCustomizer(registry);
        CaffeineCacheManager manager = new CaffeineCacheManager("payments", "users");
        manager.getCache("payments");
        manager.getCache("users");

        customizer.postProcessAfterInitialization(manager, "cacheManager");

        assertThat(registry.find("cache.gets").tag("cache", "payments").meters())
                .isNotEmpty();
        assertThat(registry.find("cache.gets").tag("cache", "users").meters()).isNotEmpty();
    }

    @Test
    void leaves_non_caffeine_beans_untouched() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PulseCaffeineCacheCustomizer customizer = new PulseCaffeineCacheCustomizer(registry);

        Object result = customizer.postProcessAfterInitialization("not-a-cache-manager", "stringBean");

        assertThat(result).isEqualTo("not-a-cache-manager");
    }
}
