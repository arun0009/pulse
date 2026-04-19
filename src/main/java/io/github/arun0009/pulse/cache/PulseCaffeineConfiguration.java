package io.github.arun0009.pulse.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-instruments {@link CaffeineCacheManager} so consumers do not have to remember to call
 * {@code recordStats()} on every Caffeine spec they ship.
 *
 * <p>What ships:
 * <ul>
 *   <li>A {@link BeanPostProcessor} that, on every {@link CaffeineCacheManager} bean, replaces the
 *       configured Caffeine builder with one that has {@code recordStats()} enabled. Existing
 *       customizations (max size, expiry, weight, etc.) survive because we wrap rather than
 *       replace the spec.
 *   <li>A second post-step that, after each cache is created, binds it to Micrometer via
 *       {@link CaffeineCacheMetrics} so {@code cache.gets}, {@code cache.puts},
 *       {@code cache.evictions}, {@code cache.hit_ratio} land on the registry without an extra
 *       configuration class.
 * </ul>
 *
 * <p>Cache-miss span events are deliberately scoped to a follow-up release: span attributes on
 * every miss can blow span size on hot caches, and the value is largely covered by the
 * cache.hit_ratio gauge that ships here. This module is opt-out via
 * {@code pulse.cache.caffeine.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
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
     * On Spring's {@link CaffeineCacheManager} initialization: enable {@code recordStats()} on
     * the configured Caffeine builder, then bind every cache the manager exposes to Micrometer.
     */
    public static final class PulseCaffeineCacheCustomizer implements BeanPostProcessor {

        private final MeterRegistry registry;

        public PulseCaffeineCacheCustomizer(MeterRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof CaffeineCacheManager manager) {
                try {
                    Caffeine<Object, Object> builder = Caffeine.newBuilder().recordStats();
                    manager.setCaffeine(builder);
                } catch (RuntimeException e) {
                    log.debug("Pulse: could not enable recordStats() on CaffeineCacheManager bean '{}'", beanName, e);
                }
                bindAllToMicrometer(manager, beanName);
            }
            return bean;
        }

        private void bindAllToMicrometer(CaffeineCacheManager manager, String beanName) {
            for (String cacheName : manager.getCacheNames()) {
                try {
                    Cache cache = manager.getCache(cacheName);
                    if (cache instanceof CaffeineCache cc) {
                        CaffeineCacheMetrics.monitor(registry, cc.getNativeCache(), cacheName, "manager", beanName);
                    }
                } catch (RuntimeException e) {
                    log.debug("Pulse: could not bind Caffeine cache '{}' to Micrometer", cacheName, e);
                }
            }
        }
    }
}
