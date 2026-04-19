package io.github.arun0009.pulse.autoconfigure;

import io.github.arun0009.pulse.actuator.PulseDiagnostics;
import io.github.arun0009.pulse.async.ExecutorConfiguration;
import io.github.arun0009.pulse.audit.AuditLogger;
import io.github.arun0009.pulse.db.PulseDbConfiguration;
import io.github.arun0009.pulse.events.SpanEvents;
import io.github.arun0009.pulse.guardrails.CardinalityFirewall;
import io.github.arun0009.pulse.guardrails.SamplingConfiguration;
import io.github.arun0009.pulse.health.OtelExporterHealthIndicator;
import io.github.arun0009.pulse.health.OtelExporterHealthRegistrar;
import io.github.arun0009.pulse.jobs.InstrumentedTaskScheduler;
import io.github.arun0009.pulse.jobs.JobRegistry;
import io.github.arun0009.pulse.jobs.JobsHealthIndicator;
import io.github.arun0009.pulse.metrics.BusinessMetrics;
import io.github.arun0009.pulse.metrics.CommonTagsConfiguration;
import io.github.arun0009.pulse.metrics.DeployInfoMetrics;
import io.github.arun0009.pulse.metrics.HistogramMeterFilter;
import io.github.arun0009.pulse.profiling.PulseProfilingConfiguration;
import io.github.arun0009.pulse.propagation.KafkaPropagationConfiguration;
import io.github.arun0009.pulse.propagation.OkHttpPropagationConfiguration;
import io.github.arun0009.pulse.propagation.RestClientPropagationConfiguration;
import io.github.arun0009.pulse.propagation.RestTemplatePropagationConfiguration;
import io.github.arun0009.pulse.propagation.WebClientPropagationConfiguration;
import io.github.arun0009.pulse.resilience.PulseResilience4jConfiguration;
import io.github.arun0009.pulse.scheduling.ContextPropagatingTaskScheduler;
import io.github.arun0009.pulse.scheduling.PulseSchedulingConfigurer;
import io.github.arun0009.pulse.shutdown.PulseOtelShutdownLifecycle;
import io.github.arun0009.pulse.slo.SloProjector;
import io.github.arun0009.pulse.slo.SloRuleGenerator;
import io.github.arun0009.pulse.startup.PulseStartupBanner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.env.Environment;

/**
 * Single, opinionated entry point that wires every Pulse subsystem.
 *
 * <p>Sub-configurations live in their own classes so they can be conditionally loaded
 * (RestTemplate, WebClient, OkHttp, Kafka, web vs. non-web, etc.) based on what is on the
 * classpath and which kind of Spring application is starting up. Everything is gated behind
 * {@code pulse.*} properties so an application can opt out of any individual piece.
 *
 * <p>Web-specific beans (servlet filters, controller advices, actuator endpoints) live in
 * {@link PulseWebAutoConfiguration} so non-web worker apps still benefit from Pulse's
 * cardinality firewall, MDC propagation across {@code @Async}/{@code @Scheduled}, audit logger,
 * and Kafka propagation.
 *
 * <p>{@link AutoConfigureAfter} pins Pulse after Boot's metrics + OpenTelemetry auto-configs so
 * we observe the {@code MeterRegistry} and {@code OpenTelemetrySdk} they create. The web tier's
 * {@code PulseExceptionHandler} carries {@code @Order(Ordered.LOWEST_PRECEDENCE)} so it acts as
 * the application-wide default {@code @RestControllerAdvice} — any user-supplied advice with a
 * higher precedence still wins.
 */
@AutoConfiguration
@AutoConfigureAfter(
        name = {
            "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
            "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetryAutoConfiguration",
            "org.springframework.boot.opentelemetry.actuate.autoconfigure.metrics.OpenTelemetryMetricsAutoConfiguration",
            "org.springframework.boot.tracing.autoconfigure.opentelemetry.OpenTelemetryTracingAutoConfiguration"
        })
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
    PulseDbConfiguration.class,
    PulseResilience4jConfiguration.class,
    PulseProfilingConfiguration.class,
})
public class PulseAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pulse.cardinality", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CardinalityFirewall pulseCardinalityFirewall(
            PulseProperties properties, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        // ObjectProvider keeps this lazy: Spring Boot's MeterRegistryPostProcessor resolves
        // MeterFilter beans during MeterRegistry construction. Eagerly injecting MeterRegistry
        // here would create a circular bean reference and fail context startup.
        return new CardinalityFirewall(properties.cardinality(), meterRegistryProvider::getObject);
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
    @ConditionalOnProperty(prefix = "pulse.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AuditLogger pulseAuditLogger() {
        return new AuditLogger();
    }

    @Bean
    @ConditionalOnMissingBean
    public DeployInfoMetrics pulseDeployInfoMetrics(
            MeterRegistry registry,
            ObjectProvider<BuildProperties> buildProperties,
            ObjectProvider<GitProperties> gitProperties) {
        return new DeployInfoMetrics(registry, buildProperties.getIfAvailable(), gitProperties.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public SloRuleGenerator pulseSloRuleGenerator(
            PulseProperties properties, @Value("${spring.application.name:unknown-service}") String serviceName) {
        return new SloRuleGenerator(properties.slo(), serviceName);
    }

    @Bean
    @ConditionalOnMissingBean
    public SloProjector pulseSloProjector(PulseProperties properties, MeterRegistry registry) {
        return new SloProjector(properties.slo(), registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public PulseDiagnostics pulseDiagnostics(
            PulseProperties properties,
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${app.env:unknown-env}") String environment,
            ObjectProvider<CardinalityFirewall> cardinalityFirewall,
            ObjectProvider<SloProjector> sloProjector,
            ObjectProvider<JobRegistry> jobRegistry) {
        String version = getClass().getPackage().getImplementationVersion();
        return new PulseDiagnostics(
                properties,
                serviceName,
                environment,
                version == null ? "dev" : version,
                cardinalityFirewall.getIfAvailable(),
                sloProjector.getIfAvailable(),
                jobRegistry.getIfAvailable());
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

    /**
     * Spring-managed fallback {@link org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler}
     * used by {@link PulseSchedulingConfigurer} when the application has not declared its own
     * {@link org.springframework.scheduling.TaskScheduler}. Registered as a bean (rather than
     * created ad-hoc inside the configurer) so Spring shuts down its thread pool on context
     * close — eliminates the "lingering pulse-scheduled-* threads" leak.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(org.springframework.scheduling.TaskScheduler.class)
    @ConditionalOnProperty(
            prefix = "pulse.async",
            name = "scheduled-propagation-enabled",
            havingValue = "true",
            matchIfMissing = true)
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler pulseTaskScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("pulse-scheduled-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /**
     * Tracks the runtime state of every {@code @Scheduled} job Pulse has observed so the
     * {@code jobs} health indicator and {@code /actuator/pulse} can report on them. Always
     * registered when jobs are enabled (default) — it has no overhead until a job actually runs.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pulse.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    public JobRegistry pulseJobRegistry() {
        return new JobRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseJobsHealthIndicator")
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnProperty(
            prefix = "pulse.jobs",
            name = "health-indicator-enabled",
            havingValue = "true",
            matchIfMissing = true)
    public HealthIndicator pulseJobsHealthIndicator(JobRegistry registry, PulseProperties properties) {
        return new JobsHealthIndicator(registry, properties.jobs());
    }

    /**
     * Wraps the application's {@link org.springframework.scheduling.TaskScheduler} with one of
     * two decorators based on {@code pulse.jobs.enabled}:
     *
     * <ul>
     *   <li>{@code true} (default) — {@link InstrumentedTaskScheduler}: context propagation +
     *       per-job metrics + registry updates.
     *   <li>{@code false} — {@link ContextPropagatingTaskScheduler}: context propagation only.
     * </ul>
     *
     * <p>The wrapper is supplied to the configurer as a function so neither decorator is
     * instantiated until {@link org.springframework.scheduling.config.ScheduledTaskRegistrar} is
     * actually being configured (lazy + matches Spring's lifecycle ordering).
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "pulse.async",
            name = "scheduled-propagation-enabled",
            havingValue = "true",
            matchIfMissing = true)
    public PulseSchedulingConfigurer pulseSchedulingConfigurer(
            org.springframework.scheduling.TaskScheduler scheduler,
            PulseProperties properties,
            ObjectProvider<JobRegistry> jobRegistry,
            ObjectProvider<MeterRegistry> meterRegistry) {
        java.util.function.UnaryOperator<org.springframework.scheduling.TaskScheduler> wrapper;
        if (properties.jobs().enabled()) {
            wrapper = ts -> new InstrumentedTaskScheduler(ts, meterRegistry.getObject(), jobRegistry.getObject());
        } else {
            wrapper = ContextPropagatingTaskScheduler::new;
        }
        return new PulseSchedulingConfigurer(scheduler, wrapper);
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    @ConditionalOnClass(OpenTelemetrySdk.class)
    @ConditionalOnProperty(
            prefix = "pulse.shutdown",
            name = "otel-flush-enabled",
            havingValue = "true",
            matchIfMissing = true)
    public PulseOtelShutdownLifecycle pulseOtelShutdownLifecycle(
            ObjectProvider<OpenTelemetrySdk> sdk, PulseProperties properties) {
        return new PulseOtelShutdownLifecycle(sdk.getIfAvailable(), properties.shutdown());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(OpenTelemetrySdk.class)
    @ConditionalOnProperty(
            prefix = "pulse.health",
            name = "otel-exporter-enabled",
            havingValue = "true",
            matchIfMissing = true)
    public OtelExporterHealthRegistrar pulseOtelExporterHealthRegistrar(ObjectProvider<OpenTelemetrySdk> sdk) {
        return new OtelExporterHealthRegistrar(sdk.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(name = "otelExporterHealthIndicator")
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnProperty(
            prefix = "pulse.health",
            name = "otel-exporter-enabled",
            havingValue = "true",
            matchIfMissing = true)
    public HealthIndicator otelExporterHealthIndicator(
            OtelExporterHealthRegistrar registrar, PulseProperties properties) {
        return new OtelExporterHealthIndicator(registrar.exporters(), properties.health());
    }
}
