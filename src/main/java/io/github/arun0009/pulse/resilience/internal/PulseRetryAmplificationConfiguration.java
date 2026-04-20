package io.github.arun0009.pulse.resilience.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.resilience.RetryDepthFilter;
import io.github.arun0009.pulse.resilience.RetryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
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

/**
 * Wires the retry-amplification subsystem.
 *
 * <p>The inbound {@link RetryDepthFilter} is independent of Resilience4j — depth can be
 * propagated by any client that sets {@code Pulse-Retry-Depth}. The outbound side is wired
 * inside the existing transport interceptors (RestTemplate, RestClient, WebClient, OkHttp,
 * Kafka) via {@code HeaderPropagation.headerToMdcKey(context, retry)}, so once
 * {@code pulse.retry.enabled=true} (the default) the depth flows through every supported
 * transport without any additional bean wiring.
 *
 * <p>Disabling {@code pulse.retry.enabled} silences the inbound filter <em>and</em> drops the
 * header from outbound propagation in one switch.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnProperty(prefix = "pulse.retry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PulseRetryAmplificationConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Filter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebBeans {

        @Bean
        @ConditionalOnMissingBean(name = "pulseRetryDepthFilter")
        public FilterRegistrationBean<RetryDepthFilter> pulseRetryDepthFilter(
                RetryProperties retry, MeterRegistry registry, ObjectProvider<Tracer> tracer) {
            RetryDepthFilter filter = new RetryDepthFilter(retry, registry, tracer.getIfAvailable(() -> Tracer.NOOP));
            FilterRegistrationBean<RetryDepthFilter> reg = new FilterRegistrationBean<>(filter);
            reg.setOrder(filter.getOrder());
            reg.addUrlPatterns("/*");
            return reg;
        }
    }
}
