package io.github.arun0009.pulse.propagation.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.core.ContextProperties;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetOutbound;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetProperties;
import io.github.arun0009.pulse.priority.PriorityProperties;
import io.github.arun0009.pulse.propagation.HeaderPropagation;
import io.github.arun0009.pulse.resilience.RetryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Map;

/**
 * Adds Pulse propagation (MDC-derived request/tenant/correlation/priority/retry headers plus the
 * remaining timeout budget) to every Apache HttpClient 5 {@link HttpClientBuilder} bean the
 * application publishes.
 *
 * <p>Most Spring Boot applications reach Apache HttpClient <em>indirectly</em> via
 * {@code HttpComponentsClientHttpRequestFactory} bolted onto a {@code RestTemplate}; those calls
 * already receive Pulse propagation through
 * {@link RestTemplatePropagationConfiguration}. This class covers the less common but strategic
 * case where an application uses Apache HttpClient directly — typically for non-Spring HTTP calls,
 * third-party SDKs that expect an injected {@code HttpClient}, or tests that exercise the raw
 * client. Without this configuration those calls leak out of the Pulse correlation graph.
 *
 * <p>Bean methods live in an inner {@code @Configuration} class so Spring does not introspect
 * their return type when {@code httpclient5} is absent from the application's classpath. The
 * enclosing class has no direct Apache HttpClient references so its mere loading is safe.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
public class ApacheHttpClient5PropagationConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HttpClientBuilder.class)
    static class Beans {

        @Bean
        @ConditionalOnMissingBean(name = "pulseApacheHttpClient5BuilderInstrumenter")
        // static: same reason as OkHttpPropagationConfiguration — a non-static BPP factory forces
        // the Beans @Configuration class to be instantiated before every other BPP can see it,
        // which disables auto-proxying on the class and surfaces as a BeanPostProcessorChecker
        // warning at startup.
        public static BeanPostProcessor pulseApacheHttpClient5BuilderInstrumenter(
                ContextProperties context,
                RetryProperties retry,
                PriorityProperties priority,
                TimeoutBudgetProperties timeoutBudget,
                ObjectProvider<MeterRegistry> registry) {
            Map<String, String> headerMap = HeaderPropagation.headerToMdcKey(context, retry, priority);
            PulseApacheHttpClient5Interceptor interceptor = new PulseApacheHttpClient5Interceptor(
                    headerMap,
                    timeoutBudget.outboundHeader(),
                    timeoutBudget.enabled(),
                    new TimeoutBudgetOutbound(registry.getIfAvailable()));
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof HttpClientBuilder builder) {
                        // addRequestInterceptorFirst: we want the Pulse headers on the request
                        // before any user-registered retry / redirect / auth interceptor
                        // decides what to do — that way retries carry the same correlation id
                        // as the original attempt.
                        builder.addRequestInterceptorFirst(interceptor);
                    }
                    return bean;
                }
            };
        }

        /**
         * Publish the interceptor as a plain bean so apps that construct their
         * {@link HttpClientBuilder} outside the Spring context (e.g. in a {@code @Bean} method
         * body that never stores the intermediate builder) can still wire Pulse propagation
         * with a single {@code .addRequestInterceptorFirst(pulseApacheHttpClient5Interceptor)}
         * call.
         */
        @Bean
        @ConditionalOnMissingBean
        public PulseApacheHttpClient5Interceptor pulseApacheHttpClient5Interceptor(
                ContextProperties context,
                RetryProperties retry,
                PriorityProperties priority,
                TimeoutBudgetProperties timeoutBudget,
                ObjectProvider<MeterRegistry> registry) {
            return new PulseApacheHttpClient5Interceptor(
                    HeaderPropagation.headerToMdcKey(context, retry, priority),
                    timeoutBudget.outboundHeader(),
                    timeoutBudget.enabled(),
                    new TimeoutBudgetOutbound(registry.getIfAvailable()));
        }
    }

    /**
     * Apache HttpClient 5 request interceptor that copies the current {@link MDC} onto outbound
     * requests as configured headers, then stamps the remaining timeout budget (if any). Stateless
     * and thread-safe; may be shared across all HttpClient instances.
     */
    public static class PulseApacheHttpClient5Interceptor implements HttpRequestInterceptor {

        private final Map<String, String> headerMap;
        private final String budgetHeader;
        private final boolean budgetEnabled;
        private final TimeoutBudgetOutbound budgetHelper;

        public PulseApacheHttpClient5Interceptor(
                Map<String, String> headerMap,
                String budgetHeader,
                boolean budgetEnabled,
                TimeoutBudgetOutbound budgetHelper) {
            this.headerMap = headerMap;
            this.budgetHeader = budgetHeader;
            this.budgetEnabled = budgetEnabled;
            this.budgetHelper = budgetHelper;
        }

        @Override
        public void process(HttpRequest request, EntityDetails entity, HttpContext context)
                throws HttpException, IOException {
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            if (mdc != null && !mdc.isEmpty()) {
                headerMap.forEach((header, mdcKey) -> {
                    String value = mdc.get(mdcKey);
                    // Existence check over overwrite: upstream code that has already set
                    // one of these headers by hand wins. This matches the OkHttp / RestTemplate
                    // propagation semantics so the wire format is identical across transports.
                    if (value != null && !request.containsHeader(header)) {
                        request.setHeader(header, value);
                    }
                });
            }
            if (budgetEnabled && !request.containsHeader(budgetHeader)) {
                budgetHelper
                        .resolveRemaining("apache-hc5")
                        .ifPresent(remaining -> request.setHeader(budgetHeader, Long.toString(remaining.toMillis())));
            }
        }
    }
}
