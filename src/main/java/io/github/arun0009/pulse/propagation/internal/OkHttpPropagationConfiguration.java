package io.github.arun0009.pulse.propagation.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.core.ContextProperties;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetOutbound;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetProperties;
import io.github.arun0009.pulse.priority.PriorityProperties;
import io.github.arun0009.pulse.propagation.HeaderPropagation;
import io.github.arun0009.pulse.resilience.RetryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
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
 * If the application puts an {@link OkHttpClient.Builder} in the Spring context, Pulse instruments
 * it to copy MDC context onto outbound requests as headers <em>and</em> to propagate the remaining
 * timeout budget.
 *
 * <p>Bean methods live in an inner class so Spring does not introspect their return type when
 * okhttp is absent from the application classpath.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
public class OkHttpPropagationConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OkHttpClient.class)
    static class Beans {

        @Bean
        @ConditionalOnMissingBean(name = "pulseOkHttpBuilderInstrumenter")
        // static: Spring warns otherwise because a BeanPostProcessor factory on a non-static
        // method forces Beans to be instantiated before all other BPPs can see it — which
        // silently disables AOP/auto-proxying on this @Configuration class.
        public static BeanPostProcessor pulseOkHttpBuilderInstrumenter(
                ContextProperties context,
                RetryProperties retry,
                PriorityProperties priority,
                TimeoutBudgetProperties timeoutBudget,
                ObjectProvider<MeterRegistry> registry) {
            Map<String, String> headerMap = HeaderPropagation.headerToMdcKey(context, retry, priority);
            PulseOkHttpInterceptor interceptor = new PulseOkHttpInterceptor(
                    headerMap,
                    timeoutBudget.outboundHeader(),
                    timeoutBudget.enabled(),
                    new TimeoutBudgetOutbound(registry.getIfAvailable()));
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof OkHttpClient.Builder builder) {
                        builder.addInterceptor(interceptor);
                    }
                    return bean;
                }
            };
        }

        @Bean
        @ConditionalOnMissingBean
        public PulseOkHttpInterceptor pulseOkHttpInterceptor(
                ContextProperties context,
                RetryProperties retry,
                PriorityProperties priority,
                TimeoutBudgetProperties timeoutBudget,
                ObjectProvider<MeterRegistry> registry) {
            return new PulseOkHttpInterceptor(
                    HeaderPropagation.headerToMdcKey(context, retry, priority),
                    timeoutBudget.outboundHeader(),
                    timeoutBudget.enabled(),
                    new TimeoutBudgetOutbound(registry.getIfAvailable()));
        }
    }

    /** Convenience interceptor exposed as a bean so apps can add it manually. */
    public static class PulseOkHttpInterceptor implements Interceptor {
        private final Map<String, String> headerMap;
        private final String budgetHeader;
        private final boolean budgetEnabled;
        private final TimeoutBudgetOutbound budgetHelper;

        public PulseOkHttpInterceptor(
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
        public Response intercept(Chain chain) throws IOException {
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            Request original = chain.request();
            Request.Builder builder = original.newBuilder();
            if (mdc != null && !mdc.isEmpty()) {
                headerMap.forEach((header, mdcKey) -> {
                    String value = mdc.get(mdcKey);
                    if (value != null && original.header(header) == null) {
                        builder.header(header, value);
                    }
                });
            }
            if (budgetEnabled && original.header(budgetHeader) == null) {
                budgetHelper
                        .resolveRemaining("okhttp")
                        .ifPresent(remaining -> builder.header(budgetHeader, Long.toString(remaining.toMillis())));
            }
            return chain.proceed(builder.build());
        }
    }
}
