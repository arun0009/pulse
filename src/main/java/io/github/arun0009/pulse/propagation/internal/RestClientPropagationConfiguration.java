package io.github.arun0009.pulse.propagation.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.core.ContextProperties;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetOutboundInterceptor;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetProperties;
import io.github.arun0009.pulse.priority.PriorityProperties;
import io.github.arun0009.pulse.propagation.HeaderPropagation;
import io.github.arun0009.pulse.resilience.RetryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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
@AutoConfiguration(after = PulseAutoConfiguration.class)
public class RestClientPropagationConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({RestClient.class, RestClientCustomizer.class})
    static class Beans {

        /**
         * MDC-header propagation customizer. Ordered {@code 0} so it installs its interceptor
         * <em>before</em> {@link #pulseTimeoutBudgetRestClientCustomizer}; see the
         * corresponding RestTemplate customizer for the full rationale.
         */
        @Bean
        @ConditionalOnMissingBean(name = "pulseRestClientCustomizer")
        @Order(0)
        public RestClientCustomizer pulseRestClientCustomizer(
                ContextProperties context, RetryProperties retry, PriorityProperties priority) {
            Map<String, String> headerMap = HeaderPropagation.headerToMdcKey(context, retry, priority);
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

        /**
         * Timeout-budget propagation customizer. Ordered after {@link
         * #pulseRestClientCustomizer} so the MDC headers are in place before the deadline
         * header lands.
         */
        @Bean
        @ConditionalOnMissingBean(name = "pulseTimeoutBudgetRestClientCustomizer")
        @ConditionalOnProperty(
                prefix = "pulse.timeout-budget",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        @Order(100)
        public RestClientCustomizer pulseTimeoutBudgetRestClientCustomizer(
                TimeoutBudgetProperties timeoutBudget, MeterRegistry registry) {
            TimeoutBudgetOutboundInterceptor interceptor =
                    new TimeoutBudgetOutboundInterceptor(timeoutBudget, registry, "restclient");
            return builder -> builder.requestInterceptor(interceptor);
        }
    }
}
