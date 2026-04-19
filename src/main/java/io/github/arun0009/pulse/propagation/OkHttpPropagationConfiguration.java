package io.github.arun0009.pulse.propagation;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetOutbound;
import io.micrometer.core.instrument.MeterRegistry;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
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
@Configuration(proxyBeanMethods = false)
public class OkHttpPropagationConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OkHttpClient.class)
    static class Beans {

        @Bean
        public BeanPostProcessor pulseOkHttpBuilderInstrumenter(
                PulseProperties properties, ObjectProvider<MeterRegistry> registry) {
            Map<String, String> headerMap = HeaderPropagation.headerToMdcKey(properties.context());
            PulseOkHttpInterceptor interceptor = new PulseOkHttpInterceptor(
                    headerMap,
                    properties.timeoutBudget().outboundHeader(),
                    properties.timeoutBudget().enabled(),
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
        public PulseOkHttpInterceptor pulseOkHttpInterceptor(
                PulseProperties properties, ObjectProvider<MeterRegistry> registry) {
            return new PulseOkHttpInterceptor(
                    HeaderPropagation.headerToMdcKey(properties.context()),
                    properties.timeoutBudget().outboundHeader(),
                    properties.timeoutBudget().enabled(),
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
