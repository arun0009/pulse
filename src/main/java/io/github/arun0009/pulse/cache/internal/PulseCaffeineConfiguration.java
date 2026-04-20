package io.github.arun0009.pulse.cache.internal;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;

/**
 * Auto-binds every {@link CaffeineCacheManager} bean to Micrometer so {@code cache.gets},
 * {@code cache.puts}, {@code cache.evictions}, and {@code cache.hit_ratio} land on the registry
 * with no extra configuration.
 *
 * <p>Pulse <em>intentionally</em> does <strong>not</strong> mutate the application's Caffeine
 * spec. Earlier iterations called {@code manager.setCaffeine(Caffeine.newBuilder().recordStats())}
 * to "auto-enable" stats — that silently discarded the caller's {@code maximumSize},
 * {@code expireAfterWrite}, weighers, removal listeners, and every other policy in their builder.
 * That trade is unacceptable: Pulse must never make a cache configuration change that the
 * operator did not author. If a Caffeine cache lacks {@code recordStats()} the bind happens
 * anyway and the resulting hit/miss meters simply report zero — Pulse logs a one-time WARN per
 * manager bean so the missing call is visible.
 *
 * <p>Opt out via {@code pulse.cache.caffeine.enabled=false}.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnClass({CaffeineCacheManager.class, Caffeine.class})
@ConditionalOnProperty(prefix = "pulse.cache.caffeine", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PulseCaffeineConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PulseCaffeineConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public PulseCaffeineCacheCustomizer pulseCaffeineCacheCustomizer(MeterRegistry registry) {
        return new PulseCaffeineCacheCustomizer(registry);
    }

    /**
     * Binds every {@link CaffeineCache} exposed by a {@link CaffeineCacheManager} bean to
     * Micrometer at bean-initialization time. This never mutates the manager's Caffeine spec.
     */
    public static final class PulseCaffeineCacheCustomizer implements BeanPostProcessor {

        private final MeterRegistry registry;

        public PulseCaffeineCacheCustomizer(MeterRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof CaffeineCacheManager manager) {
                bindAllToMicrometer(manager, beanName);
            }
            return bean;
        }

        private void bindAllToMicrometer(CaffeineCacheManager manager, String beanName) {
            boolean warnedRecordStats = false;
            for (String cacheName : manager.getCacheNames()) {
                try {
                    Cache cache = manager.getCache(cacheName);
                    if (cache instanceof CaffeineCache cc) {
                        com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache = cc.getNativeCache();
                        if (!warnedRecordStats && !nativeCache.policy().isRecordingStats()) {
                            log.warn(
                                    "Pulse: CaffeineCacheManager bean '{}' is configured without recordStats(); cache hit/miss meters will report zero. "
                                            + "Add `.recordStats()` to your Caffeine builder to enable rich cache metrics.",
                                    beanName);
                            warnedRecordStats = true;
                        }
                        CaffeineCacheMetrics.monitor(registry, nativeCache, cacheName, "manager", beanName);
                    }
                } catch (RuntimeException e) {
                    log.debug("Pulse: could not bind Caffeine cache '{}' to Micrometer", cacheName, e);
                }
            }
        }
    }
}
