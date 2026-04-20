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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

        @Bean
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

        @Bean
        @ConditionalOnProperty(
                prefix = "pulse.timeout-budget",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        public RestTemplateCustomizer pulseTimeoutBudgetRestTemplateCustomizer(
                TimeoutBudgetProperties timeoutBudget, MeterRegistry registry) {
            TimeoutBudgetOutboundInterceptor interceptor =
                    new TimeoutBudgetOutboundInterceptor(timeoutBudget, registry, "resttemplate");
            return restTemplate -> restTemplate.getInterceptors().add(interceptor);
        }
    }
}
