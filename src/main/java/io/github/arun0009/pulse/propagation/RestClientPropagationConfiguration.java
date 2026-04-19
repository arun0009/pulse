package io.github.arun0009.pulse.propagation;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetOutboundInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Same propagation contract as {@link RestTemplatePropagationConfiguration} but for the modern
 * Spring 6 / Spring Boot 3.2+ {@link RestClient}. Without this, an application that switched from
 * {@code RestTemplate} would silently lose Pulse's MDC + timeout-budget propagation on outbound
 * calls.
 *
 * <p>Bean methods live in an inner class so Spring does not introspect their return type when
 * {@code spring-boot-restclient} is absent from the application classpath.
 */
@Configuration(proxyBeanMethods = false)
public class RestClientPropagationConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({RestClient.class, RestClientCustomizer.class})
    static class Beans {

        @Bean
        public RestClientCustomizer pulseRestClientCustomizer(PulseProperties properties) {
            Map<String, String> headerMap = HeaderPropagation.headerToMdcKey(properties.context());
            return builder -> builder.requestInterceptor((request, body, execution) -> {
                Map<String, String> mdc = MDC.getCopyOfContextMap();
                if (mdc != null) {
                    headerMap.forEach((header, mdcKey) -> {
                        String value = mdc.get(mdcKey);
                        if (value != null && request.getHeaders().get(header) == null) {
                            request.getHeaders().add(header, value);
                        }
                    });
                }
                return execution.execute(request, body);
            });
        }

        @Bean
        @ConditionalOnProperty(
                prefix = "pulse.timeout-budget",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        public RestClientCustomizer pulseTimeoutBudgetRestClientCustomizer(
                PulseProperties properties, MeterRegistry registry) {
            TimeoutBudgetOutboundInterceptor interceptor =
                    new TimeoutBudgetOutboundInterceptor(properties.timeoutBudget(), registry);
            return builder -> builder.requestInterceptor(interceptor);
        }
    }
}
