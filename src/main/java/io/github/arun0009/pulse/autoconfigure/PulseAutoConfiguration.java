package io.github.arun0009.pulse.autoconfigure;

import io.github.arun0009.pulse.actuator.PulseDiagnostics;
import io.github.arun0009.pulse.actuator.PulseEndpoint;
import io.github.arun0009.pulse.actuator.PulseUiEndpoint;
import io.github.arun0009.pulse.async.ExecutorConfiguration;
import io.github.arun0009.pulse.audit.AuditLogger;
import io.github.arun0009.pulse.core.ContextContributor;
import io.github.arun0009.pulse.core.PulseRequestContextFilter;
import io.github.arun0009.pulse.core.TraceGuardFilter;
import io.github.arun0009.pulse.events.SpanEvents;
import io.github.arun0009.pulse.exception.PulseExceptionHandler;
import io.github.arun0009.pulse.guardrails.CardinalityFirewall;
import io.github.arun0009.pulse.guardrails.SamplingConfiguration;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetFilter;
import io.github.arun0009.pulse.metrics.BusinessMetrics;
import io.github.arun0009.pulse.metrics.CommonTagsConfiguration;
import io.github.arun0009.pulse.metrics.HistogramMeterFilter;
import io.github.arun0009.pulse.propagation.KafkaPropagationConfiguration;
import io.github.arun0009.pulse.propagation.OkHttpPropagationConfiguration;
import io.github.arun0009.pulse.propagation.RestClientPropagationConfiguration;
import io.github.arun0009.pulse.propagation.RestTemplatePropagationConfiguration;
import io.github.arun0009.pulse.propagation.WebClientPropagationConfiguration;
import io.github.arun0009.pulse.slo.SloRuleGenerator;
import io.github.arun0009.pulse.startup.PulseStartupBanner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Single, opinionated entry point that wires every Pulse subsystem.
 *
 * <p>Sub-configurations live in their own classes so they can be conditionally loaded
 * (RestTemplate, WebClient, OkHttp, etc.) based on what is on the classpath. Everything is gated
 * behind {@code pulse.*} properties so an application can opt out of any individual piece.
 */
@AutoConfiguration
@ConditionalOnWebApplication
@EnableConfigurationProperties(PulseProperties.class)
@ImportRuntimeHints(PulseRuntimeHints.class)
@Import({
    SamplingConfiguration.class,
    CommonTagsConfiguration.class,
    ExecutorConfiguration.class,
    RestTemplatePropagationConfiguration.class,
    RestClientPropagationConfiguration.class,
    WebClientPropagationConfiguration.class,
    OkHttpPropagationConfiguration.class,
    KafkaPropagationConfiguration.class,
})
public class PulseAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pulse.context", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PulseRequestContextFilter pulseRequestContextFilter(
            PulseProperties properties,
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${app.env:unknown-env}") String environment,
            List<ContextContributor> contributors) {
        return new PulseRequestContextFilter(serviceName, environment, properties.context(), contributors);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pulse.trace-guard", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TraceGuardFilter pulseTraceGuardFilter(MeterRegistry registry, PulseProperties properties) {
        return new TraceGuardFilter(registry, properties.traceGuard());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "pulse.timeout-budget",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public TimeoutBudgetFilter pulseTimeoutBudgetFilter(PulseProperties properties) {
        return new TimeoutBudgetFilter(properties.timeoutBudget());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pulse.cardinality", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CardinalityFirewall pulseCardinalityFirewall(PulseProperties properties, MeterRegistry registry) {
        return new CardinalityFirewall(properties.cardinality(), registry);
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseHistogramMeterFilter")
    @ConditionalOnProperty(prefix = "pulse.histograms", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MeterFilter pulseHistogramMeterFilter(PulseProperties properties) {
        return new HistogramMeterFilter(properties.histograms());
    }

    @Bean
    @ConditionalOnMissingBean
    public BusinessMetrics pulseBusinessMetrics(MeterRegistry registry) {
        return new BusinessMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pulse.wide-events", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SpanEvents pulseSpanEvents(MeterRegistry registry, PulseProperties properties) {
        return new SpanEvents(registry, properties.wideEvents());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "pulse.exception-handler",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public PulseExceptionHandler pulseExceptionHandler() {
        return new PulseExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pulse.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AuditLogger pulseAuditLogger() {
        return new AuditLogger();
    }

    @Bean
    @ConditionalOnMissingBean
    public PulseDiagnostics pulseDiagnostics(
            PulseProperties properties,
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${app.env:unknown-env}") String environment,
            ObjectProvider<CardinalityFirewall> cardinalityFirewall) {
        String version = getClass().getPackage().getImplementationVersion();
        return new PulseDiagnostics(
                properties,
                serviceName,
                environment,
                version == null ? "dev" : version,
                cardinalityFirewall.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public SloRuleGenerator pulseSloRuleGenerator(
            PulseProperties properties, @Value("${spring.application.name:unknown-service}") String serviceName) {
        return new SloRuleGenerator(properties.slo(), serviceName);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    @ConditionalOnAvailableEndpoint
    @ConditionalOnMissingBean
    public PulseEndpoint pulseEndpoint(PulseDiagnostics diagnostics, SloRuleGenerator sloRules) {
        return new PulseEndpoint(diagnostics, sloRules);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint")
    @ConditionalOnAvailableEndpoint
    @ConditionalOnMissingBean
    public PulseUiEndpoint pulseUiEndpoint(PulseDiagnostics diagnostics, SloRuleGenerator sloRules) {
        return new PulseUiEndpoint(diagnostics, sloRules);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pulse.banner", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PulseStartupBanner pulseStartupBanner(
            PulseProperties properties,
            Environment env,
            @Value("${spring.application.name:unknown-service}") String serviceName) {
        return new PulseStartupBanner(properties, env, serviceName);
    }
}
