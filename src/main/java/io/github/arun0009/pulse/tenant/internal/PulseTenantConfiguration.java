package io.github.arun0009.pulse.tenant.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.tenant.HeaderTenantExtractor;
import io.github.arun0009.pulse.tenant.JwtClaimTenantExtractor;
import io.github.arun0009.pulse.tenant.SubdomainTenantExtractor;
import io.github.arun0009.pulse.tenant.TenantContextFilter;
import io.github.arun0009.pulse.tenant.TenantExtractor;
import io.github.arun0009.pulse.tenant.TenantObservationFilter;
import io.github.arun0009.pulse.tenant.TenantObservationRegistrar;
import io.github.arun0009.pulse.tenant.TenantProperties;
import io.github.arun0009.pulse.tenant.TenantSortedExtractorsHolder;
import io.github.arun0009.pulse.tenant.TenantTagCardinalityFilter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the multi-tenant context subsystem.
 *
 * <p>Built-in extractors are registered as {@code @ConditionalOnProperty} beans so an
 * application opts each one in independently. The header extractor is on by default and
 * reads the header named by {@link TenantProperties.Header#name()} (default
 * {@code Pulse-Tenant-Id}, RFC 6648 — no {@code X-} prefix).
 *
 * <p>Metric tagging (via {@link TenantObservationFilter}) is gated on the operator naming
 * the meters they want tagged in {@code pulse.tenant.tag-meters} — empty list = no tagging,
 * which keeps the default cardinality cost at zero.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnProperty(prefix = "pulse.tenant", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PulseTenantConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "pulseHeaderTenantExtractor")
    @ConditionalOnProperty(
            prefix = "pulse.tenant.header",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public TenantExtractor pulseHeaderTenantExtractor(TenantProperties properties) {
        return new HeaderTenantExtractor(properties.header().name());
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseJwtClaimTenantExtractor")
    @ConditionalOnProperty(prefix = "pulse.tenant.jwt", name = "enabled", havingValue = "true")
    public TenantExtractor pulseJwtClaimTenantExtractor(TenantProperties properties) {
        return new JwtClaimTenantExtractor(properties.jwt().claim());
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseSubdomainTenantExtractor")
    @ConditionalOnProperty(prefix = "pulse.tenant.subdomain", name = "enabled", havingValue = "true")
    public TenantExtractor pulseSubdomainTenantExtractor(TenantProperties properties) {
        return new SubdomainTenantExtractor(properties.subdomain().index());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Filter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebBeans {

        @Bean
        @ConditionalOnMissingBean(name = "pulseTenantContextFilter")
        public FilterRegistrationBean<TenantContextFilter> pulseTenantContextFilter(
                ObjectProvider<TenantExtractor> extractors, TenantProperties properties) {
            List<TenantExtractor> ordered = extractors.orderedStream().toList();
            // orderedStream() honors @Order / Ordered, so the highest-priority extractor runs
            // first — the resolution order documented in the multi-tenant design note.
            TenantContextFilter filter = new TenantContextFilter(ordered, properties);
            FilterRegistrationBean<TenantContextFilter> reg = new FilterRegistrationBean<>(filter);
            reg.setOrder(filter.getOrder());
            reg.addUrlPatterns("/*");
            return reg;
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantTagCardinalityFilter pulseTenantTagCardinalityFilter(TenantProperties properties) {
        return new TenantTagCardinalityFilter(properties);
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseTenantTagCardinalityMeterFilter")
    public MeterFilter pulseTenantTagCardinalityMeterFilter(TenantTagCardinalityFilter filter) {
        return filter;
    }

    @Bean
    @ConditionalOnClass(ObservationRegistry.class)
    @ConditionalOnMissingBean(TenantObservationFilter.class)
    public TenantObservationFilter pulseTenantObservationFilter(TenantProperties properties) {
        return new TenantObservationFilter(properties.tagMeters());
    }

    @Bean
    @ConditionalOnClass(ObservationRegistry.class)
    @ConditionalOnMissingBean
    public TenantObservationRegistrar pulseTenantObservationRegistrar(
            ObjectProvider<ObservationRegistry> registry, TenantObservationFilter filter) {
        return new TenantObservationRegistrar(registry.getIfAvailable(), filter);
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseTenantSortedExtractors")
    public TenantSortedExtractorsHolder pulseTenantSortedExtractors(ObjectProvider<TenantExtractor> extractors) {
        // Holder bean so non-web tests can still see the sorted extractor chain.
        // ObjectProvider.orderedStream() already honors @Order / Ordered.
        return new TenantSortedExtractorsHolder(extractors.orderedStream().toList());
    }
}
