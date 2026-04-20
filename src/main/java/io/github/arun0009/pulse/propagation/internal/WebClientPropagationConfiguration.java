package io.github.arun0009.pulse.propagation.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.core.ContextProperties;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetOutbound;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetProperties;
import io.github.arun0009.pulse.priority.PriorityProperties;
import io.github.arun0009.pulse.propagation.HeaderPropagation;
import io.github.arun0009.pulse.resilience.RetryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * Adds an exchange filter to every {@link WebClient.Builder} bean that copies Pulse MDC keys onto
 * outbound requests as headers and propagates the remaining timeout budget. Operates in servlet
 * (blocking) contexts where MDC is reliable; reactive code paths should rely on Reactor Context
 * propagation.
 *
 * <p>Bean methods live in an inner class so Spring does not introspect their return type when
 * {@code spring-boot-webclient} is absent from the application classpath.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
public class WebClientPropagationConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({WebClient.class, WebClientCustomizer.class})
    static class Beans {

        /**
         * Combined MDC+TimeoutBudget propagation customizer. Ordered {@code 0} so any
         * user-defined {@link WebClientCustomizer} without an explicit order (Spring's default
         * is {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE}) runs after Pulse's,
         * i.e. sees the Pulse-authored headers already on the request builder.
         */
        @Bean
        @ConditionalOnMissingBean(name = "pulseWebClientCustomizer")
        @Order(0)
        public WebClientCustomizer pulseWebClientCustomizer(
                ContextProperties context,
                RetryProperties retry,
                PriorityProperties priority,
                TimeoutBudgetProperties timeoutBudget,
                ObjectProvider<MeterRegistry> registry) {
            Map<String, String> headerMap = HeaderPropagation.headerToMdcKey(context, retry, priority);
            String budgetHeader = timeoutBudget.outboundHeader();
            boolean budgetEnabled = timeoutBudget.enabled();
            TimeoutBudgetOutbound budgetHelper = new TimeoutBudgetOutbound(registry.getIfAvailable());
            return builder -> builder.filter(filter(headerMap, budgetHeader, budgetEnabled, budgetHelper));
        }

        private static ExchangeFilterFunction filter(
                Map<String, String> headerMap,
                String budgetHeader,
                boolean budgetEnabled,
                TimeoutBudgetOutbound budgetHelper) {
            return (request, next) -> {
                Map<String, String> mdc = MDC.getCopyOfContextMap();
                ClientRequest.Builder builder = ClientRequest.from(request);
                if (mdc != null && !mdc.isEmpty()) {
                    headerMap.forEach((header, mdcKey) -> {
                        String value = mdc.get(mdcKey);
                        if (value != null && request.headers().getFirst(header) == null) {
                            builder.header(header, value);
                        }
                    });
                }
                if (budgetEnabled && request.headers().getFirst(budgetHeader) == null) {
                    budgetHelper
                            .resolveRemaining("webclient")
                            .ifPresent(remaining -> builder.header(budgetHeader, Long.toString(remaining.toMillis())));
                }
                return next.exchange(builder.build());
            };
        }
    }
}
