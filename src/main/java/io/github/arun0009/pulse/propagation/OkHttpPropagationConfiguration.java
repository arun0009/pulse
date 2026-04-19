package io.github.arun0009.pulse.propagation;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Map;

/**
 * If the application puts an {@link OkHttpClient.Builder} in the Spring context, Pulse instruments
 * it to copy MDC context onto outbound requests as headers.
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
        public BeanPostProcessor pulseOkHttpBuilderInstrumenter(PulseProperties properties) {
            // Headers are resolved once from PulseProperties — including any consumer
            // customization of header names (e.g. pulse.context.requestIdHeader) and
            // the canonical Idempotency-Key — so OkHttp parity matches RestTemplate/WebClient.
            Map<String, String> headerMap = HeaderPropagation.headerToMdcKey(properties.context());
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof OkHttpClient.Builder builder) {
                        builder.addInterceptor(new PulseOkHttpInterceptor(headerMap));
                    }
                    return bean;
                }
            };
        }

        @Bean
        public PulseOkHttpInterceptor pulseOkHttpInterceptor(PulseProperties properties) {
            return new PulseOkHttpInterceptor(HeaderPropagation.headerToMdcKey(properties.context()));
        }
    }

    /** Convenience interceptor exposed as a bean so apps can add it manually. */
    public static class PulseOkHttpInterceptor implements Interceptor {
        private final Map<String, String> headerMap;

        public PulseOkHttpInterceptor(Map<String, String> headerMap) {
            this.headerMap = headerMap;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            if (mdc == null || mdc.isEmpty()) return chain.proceed(chain.request());
            Request original = chain.request();
            Request.Builder builder = original.newBuilder();
            headerMap.forEach((header, mdcKey) -> {
                String value = mdc.get(mdcKey);
                if (value != null && original.header(header) == null) {
                    builder.header(header, value);
                }
            });
            return chain.proceed(builder.build());
        }
    }
}
