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
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Adds an interceptor to every {@link RestTemplate} bean (constructed via Spring's {@code
 * RestTemplateBuilder}) that copies the current MDC context onto outbound requests as headers.
 * Trace context (traceparent) is already handled by Spring Boot's OTel starter; this is purely
 * about the application-level identifiers that operators need for correlation.
 *
 * <p>Bean methods live in an inner class to prevent Spring from introspecting their return type
 * (and triggering {@link NoClassDefFoundError}) when {@code spring-boot-restclient} is not on the
 * application's classpath.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
public class RestTemplatePropagationConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({RestTemplate.class, RestTemplateCustomizer.class})
    static class Beans {

        /**
         * MDC-header propagation customizer. Ordered {@code 0} so it installs its interceptor
         * <em>before</em> {@link #pulseTimeoutBudgetRestTemplateCustomizer} — RestTemplate runs
         * interceptors in insertion order, and the deadline header should only be stamped onto a
         * request that already carries the caller identity headers, so a Pulse-equipped downstream
         * service can correlate the timeout with the request id that triggered it.
         */
        @Bean
        @ConditionalOnMissingBean(name = "pulseRestTemplateCustomizer")
        @Order(0)
        public RestTemplateCustomizer pulseRestTemplateCustomizer(
                ContextProperties context, RetryProperties retry, PriorityProperties priority) {
            Map<String, String> headerMap = HeaderPropagation.headerToMdcKey(context, retry, priority);
            return restTemplate -> restTemplate.getInterceptors().add((request, body, execution) -> {
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
         * #pulseRestTemplateCustomizer} so it appends its interceptor to the tail of the chain;
         * that guarantees the deadline header is only stamped once the MDC headers — which
         * downstream Pulse instances correlate the deadline with — have already been written.
         */
        @Bean
        @ConditionalOnMissingBean(name = "pulseTimeoutBudgetRestTemplateCustomizer")
        @ConditionalOnProperty(
                prefix = "pulse.timeout-budget",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        @Order(100)
        public RestTemplateCustomizer pulseTimeoutBudgetRestTemplateCustomizer(
                TimeoutBudgetProperties timeoutBudget, MeterRegistry registry) {
            TimeoutBudgetOutboundInterceptor interceptor =
                    new TimeoutBudgetOutboundInterceptor(timeoutBudget, registry, "resttemplate");
            return restTemplate -> restTemplate.getInterceptors().add(interceptor);
        }
    }
}
