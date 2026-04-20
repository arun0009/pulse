package io.github.arun0009.pulse.autoconfigure;

import io.github.arun0009.pulse.actuator.PulseDiagnostics;
import io.github.arun0009.pulse.actuator.PulseEndpoint;
import io.github.arun0009.pulse.actuator.PulseUiEndpoint;
import io.github.arun0009.pulse.core.ContextContributor;
import io.github.arun0009.pulse.core.PulseRequestContextFilter;
import io.github.arun0009.pulse.core.PulseRequestMatcher;
import io.github.arun0009.pulse.core.TraceGuardFilter;
import io.github.arun0009.pulse.exception.ErrorFingerprintStrategy;
import io.github.arun0009.pulse.exception.PulseExceptionHandler;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetFilter;
import io.github.arun0009.pulse.slo.SloRuleGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Web-tier Pulse beans — servlet filters, controller advice, actuator endpoints.
 *
 * <p>Split out from {@link PulseAutoConfiguration} so that non-web (worker, batch, CLI)
 * applications can still benefit from Pulse's cardinality firewall, MDC propagation across
 * async hops, and Kafka propagation, without dragging in servlet API
 * dependencies. {@link ConditionalOnWebApplication} gates the entire class on the servlet
 * stack being present.
 *
 * <p>Wired to load <em>after</em> {@link PulseAutoConfiguration} so that core beans
 * (MeterRegistry, ContextContributors, PulseDiagnostics) are available to inject into the
 * filters and endpoints declared here.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@AutoConfigureAfter(PulseAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(Filter.class)
public class PulseWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pulse.context", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PulseRequestContextFilter pulseRequestContextFilter(
            PulseProperties properties,
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${app.env:unknown-env}") String environment,
            ObjectProvider<ContextContributor> contributors) {
        List<ContextContributor> list = contributors.orderedStream().toList();
        return new PulseRequestContextFilter(serviceName, environment, properties.context(), list);
    }

    @Bean
    @ConditionalOnMissingBean
    public PulseRequestMatcherFactory pulseRequestMatcherFactory(BeanFactory beanFactory) {
        return new PulseRequestMatcherFactory(beanFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pulse.trace-guard", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TraceGuardFilter pulseTraceGuardFilter(
            MeterRegistry registry, PulseProperties properties, PulseRequestMatcherFactory matcherFactory) {
        PulseRequestMatcher gate =
                matcherFactory.build("trace-guard", properties.traceGuard().enabledWhen());
        return new TraceGuardFilter(registry, properties.traceGuard(), gate);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "pulse.timeout-budget",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public TimeoutBudgetFilter pulseTimeoutBudgetFilter(
            PulseProperties properties, PulseRequestMatcherFactory matcherFactory) {
        PulseRequestMatcher gate = matcherFactory.build(
                "timeout-budget", properties.timeoutBudget().enabledWhen());
        return new TimeoutBudgetFilter(properties.timeoutBudget(), gate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ErrorFingerprintStrategy pulseErrorFingerprintStrategy() {
        return ErrorFingerprintStrategy.DEFAULT;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "pulse.exception-handler",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public PulseExceptionHandler pulseExceptionHandler(
            ObjectProvider<MeterRegistry> registry,
            ErrorFingerprintStrategy fingerprintStrategy,
            PulseProperties properties,
            PulseRequestMatcherFactory matcherFactory) {
        PulseRequestMatcher gate = matcherFactory.build(
                "exception-handler", properties.exceptionHandler().enabledWhen());
        return new PulseExceptionHandler(registry.getIfAvailable(), fingerprintStrategy, gate);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    @ConditionalOnAvailableEndpoint
    @ConditionalOnMissingBean
    public PulseEndpoint pulseEndpoint(PulseDiagnostics diagnostics, ObjectProvider<SloRuleGenerator> sloRules) {
        return new PulseEndpoint(diagnostics, sloRules.getIfAvailable());
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint")
    @ConditionalOnAvailableEndpoint
    @ConditionalOnMissingBean
    public PulseUiEndpoint pulseUiEndpoint(PulseDiagnostics diagnostics, ObjectProvider<SloRuleGenerator> sloRules) {
        return new PulseUiEndpoint(diagnostics, sloRules.getIfAvailable());
    }
}
