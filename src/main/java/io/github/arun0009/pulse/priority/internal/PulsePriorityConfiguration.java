package io.github.arun0009.pulse.priority.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.priority.PriorityProperties;
import io.github.arun0009.pulse.priority.RequestPriorityFilter;
import io.github.arun0009.pulse.priority.RequestPriorityObservationFilter;
import jakarta.servlet.Servlet;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the request-criticality subsystem.
 *
 * <p>The servlet filter ({@link RequestPriorityFilter}) and the Micrometer observation filter
 * ({@link RequestPriorityObservationFilter}) are independent: the servlet filter is required to
 * resolve the priority for inbound requests, while the observation filter only fires when the
 * operator opts in to {@code pulse.priority.tag-meters}.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnProperty(prefix = "pulse.priority", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PulsePriorityConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Servlet.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public static class Web {
        @Bean
        @ConditionalOnMissingBean
        public FilterRegistrationBean<RequestPriorityFilter> pulseRequestPriorityFilter(PriorityProperties priority) {
            FilterRegistrationBean<RequestPriorityFilter> bean =
                    new FilterRegistrationBean<>(new RequestPriorityFilter(priority));
            bean.setOrder(RequestPriorityFilter.ORDER);
            return bean;
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public RequestPriorityObservationFilter pulseRequestPriorityObservationFilter(PriorityProperties priority) {
        return new RequestPriorityObservationFilter(priority.tagMeters());
    }
}
