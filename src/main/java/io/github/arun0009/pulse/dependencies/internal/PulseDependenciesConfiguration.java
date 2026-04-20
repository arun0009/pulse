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
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.core.Ordered;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

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

    @Bean
    @ConditionalOnMissingBean
    public DependencyClassifier pulseDependencyClassifier(DependencyResolver resolver) {
        // Default classifier IS the host-table resolver. Users override the whole strategy by
        // declaring their own DependencyClassifier @Bean — host-table fallback is theirs to
        // delegate to or replace.
        return resolver;
    }

    @Bean
    @ConditionalOnMissingBean
    public DependencyOutboundRecorder pulseDependencyOutboundRecorder(
            MeterRegistry registry,
            DependencyClassifier classifier,
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
        public RestTemplateCustomizer pulseDependencyRestTemplateCustomizer(DependencyOutboundRecorder recorder) {
            DependencyClientHttpInterceptor interceptor = new DependencyClientHttpInterceptor(recorder);
            return restTemplate -> restTemplate.getInterceptors().add(interceptor);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({RestClient.class, RestClientCustomizer.class})
    static class RestClientBeans {
        @Bean
        public RestClientCustomizer pulseDependencyRestClientCustomizer(DependencyOutboundRecorder recorder) {
            DependencyClientHttpInterceptor interceptor = new DependencyClientHttpInterceptor(recorder);
            return builder -> builder.requestInterceptor(interceptor);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({WebClient.class, WebClientCustomizer.class})
    static class WebClientBeans {
        @Bean
        public WebClientCustomizer pulseDependencyWebClientCustomizer(DependencyOutboundRecorder recorder) {
            return builder -> builder.filter(DependencyExchangeFilter.filter(recorder));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OkHttpClient.class)
    static class OkHttpBeans {
        @Bean
        public BeanPostProcessor pulseDependencyOkHttpInstrumenter(
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
        public FilterRegistrationBean<RequestFanoutFilter> pulseRequestFanoutFilterRegistration(
                MeterRegistry registry,
                DependenciesProperties properties,
                ObjectProvider<PulseRequestMatcherFactory> matcherFactory) {
            PulseRequestMatcherFactory factory = matcherFactory.getIfAvailable();
            PulseRequestMatcher gate = factory == null
                    ? PulseRequestMatcher.ALWAYS
                    : factory.build("dependencies.fan-out", properties.enabledWhen());
            FilterRegistrationBean<RequestFanoutFilter> reg =
                    new FilterRegistrationBean<>(new RequestFanoutFilter(registry, properties, gate));
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
