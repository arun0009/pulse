package io.github.arun0009.pulse.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.arun0009.pulse.cache.internal.PulseCaffeineConfiguration.PulseCaffeineCacheCustomizer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import static org.assertj.core.api.Assertions.assertThat;

class PulseCaffeineConfigurationTest {

    @Test
    void does_not_replace_users_caffeine_builder_or_force_record_stats() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PulseCaffeineCacheCustomizer customizer = new PulseCaffeineCacheCustomizer(registry);
        // User-provided builder with no recordStats(); Pulse must not silently flip it on
        // (the previous implementation discarded this whole builder, including maximumSize/expiry).
        CaffeineCacheManager manager = new CaffeineCacheManager("orders");
        manager.setCaffeine(Caffeine.newBuilder().maximumSize(123));

        customizer.postProcessAfterInitialization(manager, "cacheManager");

        CaffeineCache cache = (CaffeineCache) manager.getCache("orders");
        assertThat(cache).isNotNull();
        assertThat(cache.getNativeCache().policy().isRecordingStats()).isFalse();
        assertThat(cache.getNativeCache().policy().eviction().orElseThrow().getMaximum())
                .isEqualTo(123L);
    }

    @Test
    void binds_existing_caches_with_record_stats_to_micrometer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PulseCaffeineCacheCustomizer customizer = new PulseCaffeineCacheCustomizer(registry);
        CaffeineCacheManager manager = new CaffeineCacheManager("payments", "users");
        manager.setCaffeine(Caffeine.newBuilder().recordStats());
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
