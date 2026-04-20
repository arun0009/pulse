package io.github.arun0009.pulse.dependencies.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherFactory;
import io.github.arun0009.pulse.core.PulseRequestMatcher;
import io.github.arun0009.pulse.dependencies.DependenciesProperties;
import io.github.arun0009.pulse.dependencies.DependencyClassifier;
import io.github.arun0009.pulse.dependencies.DependencyClientHttpInterceptor;
import io.github.arun0009.pulse.dependencies.DependencyExchangeFilter;
import io.github.arun0009.pulse.dependencies.DependencyHealthIndicator;
import io.github.arun0009.pulse.dependencies.DependencyOkHttpInterceptor;
import io.github.arun0009.pulse.dependencies.DependencyOutboundRecorder;
import io.github.arun0009.pulse.dependencies.DependencyResolver;
import io.github.arun0009.pulse.dependencies.RequestFanoutFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import okhttp3.OkHttpClient;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.List;

/**
 * Wires the dependency-health-map subsystem. Only the resolver and recorder are mandatory — every
 * transport's interceptor is gated on its client class being on the classpath, mirroring the
 * pattern used by {@link io.github.arun0009.pulse.propagation.internal.RestTemplatePropagationConfiguration}
 * and friends so a worker app pays nothing for transports it does not use.
 *
 * <p>Each transport-specific bean is loaded from a nested static class with its own
 * {@code @ConditionalOnClass} so Spring does not introspect a bean factory method whose return
 * type would trigger {@code NoClassDefFoundError}.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnProperty(prefix = "pulse.dependencies", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PulseDependenciesConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DependencyResolver pulseDependencyResolver(DependenciesProperties properties) {
        return new DependencyResolver(properties);
    }

    /**
     * Host-table {@link DependencyResolver} registered as the <strong>terminal</strong> link in
     * the {@link DependencyClassifier} chain. Returns the configured
     * {@code pulse.dependencies.default-name} when no upstream classifier matched, so the
     * composite never returns {@code null} on a real call.
     *
     * <p>Marked {@link Order @Order(LOWEST_PRECEDENCE)} so any user-supplied
     * {@link DependencyClassifier} bean (default order) gets a chance first.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public DependencyClassifier pulseDependencyHostTableClassifier(DependencyResolver resolver) {
        // Wrap the resolver in a small adapter so this bean's runtime type is purely
        // DependencyClassifier. If we returned the DependencyResolver instance directly,
        // Spring would see it as both a DependencyResolver and a DependencyClassifier,
        // colliding with the dedicated pulseDependencyResolver bean and breaking by-type
        // injection of DependencyResolver elsewhere.
        return new DependencyClassifier() {
            @Override
            public @Nullable String classify(URI uri) {
                return resolver.classify(uri);
            }

            @Override
            public @Nullable String classifyHost(String host) {
                return resolver.classifyHost(host);
            }
        };
    }

    /**
     * Composite that walks every {@link DependencyClassifier} bean in {@code @Order} sequence
     * and returns the first non-null classification. This is the bean every downstream
     * recorder / interceptor injects; user-supplied classifiers participate by simply being
     * declared as Spring beans.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "pulseDependencyClassifier")
    public DependencyClassifier pulseDependencyClassifier(List<DependencyClassifier> chain) {
        return new CompositeDependencyClassifier(chain);
    }

    @Bean
    @ConditionalOnMissingBean
    public DependencyOutboundRecorder pulseDependencyOutboundRecorder(
            MeterRegistry registry,
            @Qualifier("pulseDependencyClassifier") DependencyClassifier classifier,
            DependencyResolver resolver,
            DependenciesProperties properties,
            ObjectProvider<PulseRequestMatcherFactory> matcherFactory) {
        PulseRequestMatcherFactory factory = matcherFactory.getIfAvailable();
        PulseRequestMatcher gate =
                factory == null ? PulseRequestMatcher.ALWAYS : factory.build("dependencies", properties.enabledWhen());
        return new DependencyOutboundRecorder(registry, classifier, resolver, properties, gate);
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseDependencyHealthIndicator")
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(
            prefix = "pulse.dependencies.health",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public DependencyHealthIndicator pulseDependencyHealthIndicator(
            MeterRegistry registry, DependenciesProperties properties) {
        return new DependencyHealthIndicator(registry, properties.health());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({RestTemplate.class, RestTemplateCustomizer.class})
    static class RestTemplateBeans {
        @Bean
        @ConditionalOnMissingBean(name = "pulseDependencyRestTemplateCustomizer")
        public RestTemplateCustomizer pulseDependencyRestTemplateCustomizer(DependencyOutboundRecorder recorder) {
            DependencyClientHttpInterceptor interceptor = new DependencyClientHttpInterceptor(recorder);
            return restTemplate -> restTemplate.getInterceptors().add(interceptor);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({RestClient.class, RestClientCustomizer.class})
    static class RestClientBeans {
        @Bean
        @ConditionalOnMissingBean(name = "pulseDependencyRestClientCustomizer")
        public RestClientCustomizer pulseDependencyRestClientCustomizer(DependencyOutboundRecorder recorder) {
            DependencyClientHttpInterceptor interceptor = new DependencyClientHttpInterceptor(recorder);
            return builder -> builder.requestInterceptor(interceptor);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({WebClient.class, WebClientCustomizer.class})
    static class WebClientBeans {
        @Bean
        @ConditionalOnMissingBean(name = "pulseDependencyWebClientCustomizer")
        public WebClientCustomizer pulseDependencyWebClientCustomizer(DependencyOutboundRecorder recorder) {
            return builder -> builder.filter(DependencyExchangeFilter.filter(recorder));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OkHttpClient.class)
    static class OkHttpBeans {
        @Bean
        @ConditionalOnMissingBean(name = "pulseDependencyOkHttpInstrumenter")
        // static: Spring warns otherwise because a BeanPostProcessor factory on a non-static
        // method forces OkHttpBeans to be instantiated before all other BPPs can see it —
        // which silently disables AOP/auto-proxying on this @Configuration class.
        public static BeanPostProcessor pulseDependencyOkHttpInstrumenter(
                ObjectProvider<DependencyOutboundRecorder> recorder) {
            // BPP keeps recorder resolution lazy so it can be created alongside MeterRegistry.
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof OkHttpClient.Builder builder) {
                        DependencyOutboundRecorder r = recorder.getIfAvailable();
                        if (r != null) builder.addInterceptor(new DependencyOkHttpInterceptor(r));
                    }
                    return bean;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(jakarta.servlet.Filter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class FanoutFilterBeans {
        @Bean
        @ConditionalOnMissingBean(name = "pulseRequestFanoutFilterRegistration")
        public FilterRegistrationBean<RequestFanoutFilter> pulseRequestFanoutFilterRegistration(
                MeterRegistry registry,
                DependenciesProperties properties,
                ObjectProvider<PulseRequestMatcherFactory> matcherFactory,
                ObjectProvider<Tracer> tracer) {
            PulseRequestMatcherFactory factory = matcherFactory.getIfAvailable();
            PulseRequestMatcher gate = factory == null
                    ? PulseRequestMatcher.ALWAYS
                    : factory.build("dependencies.fan-out", properties.enabledWhen());
            FilterRegistrationBean<RequestFanoutFilter> reg = new FilterRegistrationBean<>(
                    new RequestFanoutFilter(registry, properties, gate, tracer.getIfAvailable(() -> Tracer.NOOP)));
            // Run very late so the thread-local is initialized after auth/MDC filters and
            // closed before request logging. HIGHEST_PRECEDENCE-1 would be wrong because the
            // outbound calls happen inside the controller, which is called by the chain — we
            // simply need to wrap the controller, which any precedence inside the filter chain
            // achieves.
            reg.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
            reg.addUrlPatterns("/*");
            return reg;
        }
    }
}
