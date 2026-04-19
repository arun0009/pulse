package io.github.arun0009.pulse.propagation;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * Adds an exchange filter to every {@link WebClient.Builder} bean that copies Pulse MDC keys onto
 * outbound requests as headers. Operates in servlet (blocking) contexts where MDC is reliable;
 * reactive code paths should rely on Reactor Context propagation.
 *
 * <p>Bean methods live in an inner class so Spring does not introspect their return type when
 * {@code spring-boot-webclient} is absent from the application classpath.
 */
@Configuration(proxyBeanMethods = false)
public class WebClientPropagationConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({WebClient.class, WebClientCustomizer.class})
    static class Beans {

        @Bean
        public WebClientCustomizer pulseWebClientCustomizer(PulseProperties properties) {
            Map<String, String> headerMap = HeaderPropagation.headerToMdcKey(properties.context());
            return builder -> builder.filter(filter(headerMap));
        }

        private static ExchangeFilterFunction filter(Map<String, String> headerMap) {
            return (request, next) -> {
                Map<String, String> mdc = MDC.getCopyOfContextMap();
                if (mdc == null || mdc.isEmpty()) {
                    return next.exchange(request);
                }
                ClientRequest.Builder builder = ClientRequest.from(request);
                headerMap.forEach((header, mdcKey) -> {
                    String value = mdc.get(mdcKey);
                    if (value != null && request.headers().getFirst(header) == null) {
                        builder.header(header, value);
                    }
                });
                return next.exchange(builder.build());
            };
        }
    }
}
