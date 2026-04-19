package io.github.arun0009.pulse.tenant;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Comparator;
import java.util.List;

/**
 * Wires the multi-tenant context subsystem.
 *
 * <p>Built-in extractors are registered as {@code @ConditionalOnProperty} beans so an
 * application opts each one in independently. The header extractor is on by default to
 * match the pre-0.3.0 behavior of {@link PulseProperties.Context#tenantIdHeader()}.
 *
 * <p>Metric tagging (via {@link TenantObservationFilter}) is gated on the operator naming
 * the meters they want tagged in {@code pulse.tenant.tag-meters} — empty list = no tagging,
 * which keeps the default cardinality cost at zero.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pulse.tenant", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PulseTenantConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "pulseHeaderTenantExtractor")
    @ConditionalOnProperty(
            prefix = "pulse.tenant.header",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public TenantExtractor pulseHeaderTenantExtractor(PulseProperties properties) {
        return new HeaderTenantExtractor(properties.tenant().header().name());
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseJwtClaimTenantExtractor")
    @ConditionalOnProperty(prefix = "pulse.tenant.jwt", name = "enabled", havingValue = "true")
    public TenantExtractor pulseJwtClaimTenantExtractor(PulseProperties properties) {
        return new JwtClaimTenantExtractor(properties.tenant().jwt().claim());
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseSubdomainTenantExtractor")
    @ConditionalOnProperty(prefix = "pulse.tenant.subdomain", name = "enabled", havingValue = "true")
    public TenantExtractor pulseSubdomainTenantExtractor(PulseProperties properties) {
        return new SubdomainTenantExtractor(properties.tenant().subdomain().index());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Filter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebBeans {

        @Bean
        public FilterRegistrationBean<TenantContextFilter> pulseTenantContextFilter(
                ObjectProvider<TenantExtractor> extractors, PulseProperties properties) {
            List<TenantExtractor> ordered = extractors.orderedStream().toList();
            // orderedStream() honors @Order / Ordered, so the highest-priority extractor runs
            // first — the resolution order documented in the multi-tenant design note.
            TenantContextFilter filter = new TenantContextFilter(ordered, properties.tenant());
            FilterRegistrationBean<TenantContextFilter> reg = new FilterRegistrationBean<>(filter);
            reg.setOrder(filter.getOrder());
            reg.addUrlPatterns("/*");
            return reg;
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantTagCardinalityFilter pulseTenantTagCardinalityFilter(PulseProperties properties) {
        return new TenantTagCardinalityFilter(properties.tenant());
    }

    @Bean
    public MeterFilter pulseTenantTagCardinalityMeterFilter(TenantTagCardinalityFilter filter) {
        return filter;
    }

    @Bean
    @ConditionalOnClass(ObservationRegistry.class)
    @ConditionalOnMissingBean(TenantObservationFilter.class)
    public TenantObservationFilter pulseTenantObservationFilter(PulseProperties properties) {
        return new TenantObservationFilter(properties.tenant().tagMeters());
    }

    @Bean
    @ConditionalOnClass(ObservationRegistry.class)
    public TenantObservationRegistrar pulseTenantObservationRegistrar(
            ObjectProvider<ObservationRegistry> registry, TenantObservationFilter filter) {
        return new TenantObservationRegistrar(registry.getIfAvailable(), filter);
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseTenantSortedExtractors")
    public TenantSortedExtractorsHolder pulseTenantSortedExtractors(ObjectProvider<TenantExtractor> extractors) {
        // Holder bean so non-web tests can still see the sorted extractor chain.
        return new TenantSortedExtractorsHolder(extractors
                .orderedStream()
                .sorted(Comparator.comparingInt(orderOf()))
                .toList());
    }

    private static java.util.function.ToIntFunction<TenantExtractor> orderOf() {
        return e -> {
            if (e instanceof org.springframework.core.Ordered o) return o.getOrder();
            return Integer.MAX_VALUE;
        };
    }

    /** Tiny holder so code that wants the resolved order can inject one bean instead of a list. */
    public record TenantSortedExtractorsHolder(List<TenantExtractor> extractors) {}
}
