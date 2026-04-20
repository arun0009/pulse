package io.github.arun0009.pulse.autoconfigure;

import io.github.arun0009.pulse.actuator.PulseDiagnostics;
import io.github.arun0009.pulse.async.AsyncProperties;
import io.github.arun0009.pulse.cache.CacheProperties;
import io.github.arun0009.pulse.container.ContainerMemoryProperties;
import io.github.arun0009.pulse.core.ContextProperties;
import io.github.arun0009.pulse.core.TraceGuardProperties;
import io.github.arun0009.pulse.db.DbProperties;
import io.github.arun0009.pulse.dependencies.DependenciesProperties;
import io.github.arun0009.pulse.enforcement.EnforcementProperties;
import io.github.arun0009.pulse.enforcement.PulseEnforcementMode;
import io.github.arun0009.pulse.events.PulseEventContext;
import io.github.arun0009.pulse.events.SpanEvents;
import io.github.arun0009.pulse.events.WideEventsProperties;
import io.github.arun0009.pulse.events.internal.PulseEventCounterObservationHandler;
import io.github.arun0009.pulse.events.internal.PulseEventLoggingObservationHandler;
import io.github.arun0009.pulse.events.internal.PulseEventSpanObservationHandler;
import io.github.arun0009.pulse.exception.ExceptionHandlerProperties;
import io.github.arun0009.pulse.fleet.ConfigHashGauge;
import io.github.arun0009.pulse.fleet.ConfigHasher;
import io.github.arun0009.pulse.guardrails.CardinalityFirewall;
import io.github.arun0009.pulse.guardrails.CardinalityProperties;
import io.github.arun0009.pulse.guardrails.SamplingProperties;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetProperties;
import io.github.arun0009.pulse.health.OtelExporterHealthIndicator;
import io.github.arun0009.pulse.health.OtelExporterHealthProperties;
import io.github.arun0009.pulse.health.OtelExporterHealthRegistrar;
import io.github.arun0009.pulse.jobs.InstrumentedTaskScheduler;
import io.github.arun0009.pulse.jobs.JobRegistry;
import io.github.arun0009.pulse.jobs.JobsHealthIndicator;
import io.github.arun0009.pulse.jobs.JobsProperties;
import io.github.arun0009.pulse.logging.HostNameProvider;
import io.github.arun0009.pulse.logging.LoggingProperties;
import io.github.arun0009.pulse.logging.ResourceAttributeResolver;
import io.github.arun0009.pulse.metrics.BusinessMetrics;
import io.github.arun0009.pulse.metrics.DeployInfoMetrics;
import io.github.arun0009.pulse.metrics.HistogramMeterFilter;
import io.github.arun0009.pulse.metrics.HistogramsProperties;
import io.github.arun0009.pulse.openfeature.OpenFeatureProperties;
import io.github.arun0009.pulse.priority.PriorityProperties;
import io.github.arun0009.pulse.profiling.ProfilingProperties;
import io.github.arun0009.pulse.propagation.KafkaPropagationProperties;
import io.github.arun0009.pulse.resilience.ResilienceProperties;
import io.github.arun0009.pulse.resilience.RetryProperties;
import io.github.arun0009.pulse.scheduling.ContextPropagatingTaskScheduler;
import io.github.arun0009.pulse.scheduling.PulseSchedulingConfigurer;
import io.github.arun0009.pulse.shutdown.InflightRequestCounter;
import io.github.arun0009.pulse.shutdown.PulseDrainObservabilityLifecycle;
import io.github.arun0009.pulse.shutdown.PulseOtelShutdownLifecycle;
import io.github.arun0009.pulse.shutdown.ShutdownProperties;
import io.github.arun0009.pulse.slo.SloProjector;
import io.github.arun0009.pulse.slo.SloProperties;
import io.github.arun0009.pulse.slo.SloRuleGenerator;
import io.github.arun0009.pulse.startup.BannerProperties;
import io.github.arun0009.pulse.startup.PulseStartupBanner;
import io.github.arun0009.pulse.tenant.TenantProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.env.Environment;

/**
 * Root Pulse auto-configuration.
 *
 * <p>This class is intentionally thin: it registers every Pulse {@code *Properties} record
 * for YAML binding, the global {@link PulseEnforcementMode}, and a small set of cross-cutting
 * beans that every other Pulse auto-config depends on (diagnostics, common metrics,
 * cardinality firewall, startup banner, job registry, shutdown lifecycles, OTel-exporter
 * health). Each feature subsystem ships its own dedicated
 * {@link AutoConfiguration @AutoConfiguration} class under
 * {@code io.github.arun0009.pulse.<feature>.internal} and is listed independently in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * so each feature shows up as its own entry in {@code /actuator/conditions}.
 *
 * <p>Web-specific beans (servlet filters, controller advices, actuator endpoints) live in
 * {@link PulseWebAutoConfiguration} so non-web worker apps still benefit from Pulse's
 * cardinality firewall, MDC propagation across {@code @Async}/{@code @Scheduled}, and Kafka
 * propagation without dragging in servlet API dependencies.
 */
@AutoConfiguration
@AutoConfigureAfter(
        name = {
            // Spring Boot 4.0.5 — use real @AutoConfiguration FQCNs so ordering is honored.
            "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
            "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration",
            "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration"
        })
@EnableConfigurationProperties({
    ContextProperties.class,
    TraceGuardProperties.class,
    SamplingProperties.class,
    AsyncProperties.class,
    KafkaPropagationProperties.class,
    ExceptionHandlerProperties.class,
    CardinalityProperties.class,
    TimeoutBudgetProperties.class,
    WideEventsProperties.class,
    LoggingProperties.class,
    BannerProperties.class,
    HistogramsProperties.class,
    SloProperties.class,
    OtelExporterHealthProperties.class,
    ShutdownProperties.class,
    JobsProperties.class,
    DbProperties.class,
    ResilienceProperties.class,
    ProfilingProperties.class,
    DependenciesProperties.class,
    TenantProperties.class,
    RetryProperties.class,
    PriorityProperties.class,
    ContainerMemoryProperties.class,
    OpenFeatureProperties.class,
    CacheProperties.class,
    EnforcementProperties.class,
    ProfilePresetsProperties.class,
})
@ImportRuntimeHints(PulseRuntimeHints.class)
public class PulseAutoConfiguration {

    /**
     * Process-wide enforce-vs-observe gate. Always created (regardless of
     * {@code pulse.enforcement.mode}) so that the {@code POST /actuator/pulse/enforcement} write
     * operation can flip between ENFORCING and DRY_RUN during an incident without a redeploy.
     */
    @Bean
    @ConditionalOnMissingBean
    public PulseEnforcementMode pulseEnforcementMode(EnforcementProperties properties) {
        return new PulseEnforcementMode(properties.mode());
    }

    /**
     * Default {@link HostNameProvider} bean — wraps {@link java.net.InetAddress#getLocalHost()}
     * with the same defensive try-catch the resource-attribute resolver uses at startup. Users
     * who want to override host detection can publish their own {@code HostNameProvider} bean
     * (replaces this one via {@code @ConditionalOnMissingBean}) <em>and</em> register the same
     * implementation in {@code META-INF/spring.factories} so it is also picked up at
     * {@code EnvironmentPostProcessor} time, before any bean exists.
     */
    @Bean
    @ConditionalOnMissingBean
    public HostNameProvider pulseHostNameProvider() {
        return () -> {
            try {
                return java.net.InetAddress.getLocalHost().getHostName();
            } catch (java.net.UnknownHostException | SecurityException ignored) {
                return null;
            }
        };
    }

    /**
     * Runtime-side {@link ResourceAttributeResolver} bean — exposes the same detection logic
     * the EPP uses at startup so application code can inject the resolved attribute map at
     * runtime (e.g., for custom health indicators or audit log enrichment). Users who
     * subclass {@code ResourceAttributeResolver} and register the subclass via
     * {@code spring.factories} should also publish it as a {@code @Bean} to keep the EPP and
     * runtime views consistent.
     */
    @Bean
    @ConditionalOnMissingBean
    public ResourceAttributeResolver pulseResourceAttributeResolver(HostNameProvider hostNameProvider) {
        return new ResourceAttributeResolver(hostNameProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pulse.cardinality", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CardinalityFirewall pulseCardinalityFirewall(
            CardinalityProperties properties,
            PulseEnforcementMode enforcement,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new CardinalityFirewall(properties, enforcement, meterRegistryProvider::getObject);
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseHistogramMeterFilter")
    @ConditionalOnProperty(prefix = "pulse.histograms", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MeterFilter pulseHistogramMeterFilter(HistogramsProperties properties) {
        return new HistogramMeterFilter(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public BusinessMetrics pulseBusinessMetrics(MeterRegistry registry) {
        return new BusinessMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseEventCounterObservationHandler")
    @ConditionalOnProperty(prefix = "pulse.wide-events", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PulseEventCounterObservationHandler pulseEventCounterObservationHandler(
            MeterRegistry registry, WideEventsProperties properties) {
        return new PulseEventCounterObservationHandler(registry, properties);
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseEventSpanObservationHandler")
    @ConditionalOnProperty(prefix = "pulse.wide-events", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PulseEventSpanObservationHandler pulseEventSpanObservationHandler(ObjectProvider<Tracer> tracer) {
        return new PulseEventSpanObservationHandler(tracer.getIfAvailable(() -> Tracer.NOOP));
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseEventLoggingObservationHandler")
    @ConditionalOnProperty(prefix = "pulse.wide-events", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PulseEventLoggingObservationHandler pulseEventLoggingObservationHandler(WideEventsProperties properties) {
        return new PulseEventLoggingObservationHandler(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pulse.wide-events", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SpanEvents pulseSpanEvents(
            WideEventsProperties properties,
            ObjectProvider<io.micrometer.observation.ObservationRegistry> observationRegistry,
            PulseEventCounterObservationHandler counterHandler,
            PulseEventSpanObservationHandler spanHandler,
            PulseEventLoggingObservationHandler loggingHandler) {
        java.util.List<io.micrometer.observation.ObservationHandler<PulseEventContext>> builtIns =
                java.util.List.of(counterHandler, spanHandler, loggingHandler);
        return new SpanEvents(
                properties,
                observationRegistry.getIfAvailable(() -> io.micrometer.observation.ObservationRegistry.NOOP),
                builtIns);
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
    @ConditionalOnProperty(prefix = "pulse.slo", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SloRuleGenerator pulseSloRuleGenerator(
            SloProperties properties, @Value("${spring.application.name:unknown-service}") String serviceName) {
        return new SloRuleGenerator(properties, serviceName);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pulse.slo", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SloProjector pulseSloProjector(SloProperties properties, MeterRegistry registry) {
        return new SloProjector(properties, registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public PulseDiagnostics pulseDiagnostics(
            PulseDiagnostics.AllProperties all,
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${app.env:unknown-env}") String environment,
            @Value("${management.tracing.sampling.probability:1.0}") double samplingProbability,
            ObjectProvider<CardinalityFirewall> cardinalityFirewall,
            ObjectProvider<SloProjector> sloProjector,
            ObjectProvider<JobRegistry> jobRegistry,
            PulseEnforcementMode enforcement,
            ObjectProvider<ResourceAttributeResolver> resourceAttributeResolver) {
        String version = getClass().getPackage().getImplementationVersion();
        return new PulseDiagnostics(
                all,
                serviceName,
                environment,
                version == null ? "dev" : version,
                samplingProbability,
                cardinalityFirewall.getIfAvailable(),
                sloProjector.getIfAvailable(),
                jobRegistry.getIfAvailable(),
                enforcement,
                resourceAttributeResolver.getIfAvailable());
    }

    /**
     * Aggregates every {@code *Properties} record into a single injectable bundle so that
     * {@link PulseDiagnostics} and {@link PulseStartupBanner} don't need 27-argument
     * constructors. Scoped internally to Pulse — not part of the public API.
     */
    @Bean
    @ConditionalOnMissingBean
    public PulseDiagnostics.AllProperties pulseAllProperties(
            ContextProperties context,
            TraceGuardProperties traceGuard,
            SamplingProperties sampling,
            AsyncProperties async,
            KafkaPropagationProperties kafka,
            ExceptionHandlerProperties exceptionHandler,
            CardinalityProperties cardinality,
            TimeoutBudgetProperties timeoutBudget,
            WideEventsProperties wideEvents,
            LoggingProperties logging,
            BannerProperties banner,
            HistogramsProperties histograms,
            SloProperties slo,
            OtelExporterHealthProperties health,
            ShutdownProperties shutdown,
            JobsProperties jobs,
            DbProperties db,
            ResilienceProperties resilience,
            ProfilingProperties profiling,
            DependenciesProperties dependencies,
            TenantProperties tenant,
            RetryProperties retry,
            PriorityProperties priority,
            ContainerMemoryProperties containerMemory,
            OpenFeatureProperties openFeature,
            CacheProperties cache) {
        return new PulseDiagnostics.AllProperties(
                context,
                traceGuard,
                sampling,
                async,
                kafka,
                exceptionHandler,
                cardinality,
                timeoutBudget,
                wideEvents,
                logging,
                banner,
                histograms,
                slo,
                health,
                shutdown,
                jobs,
                db,
                resilience,
                profiling,
                dependencies,
                tenant,
                retry,
                priority,
                containerMemory,
                openFeature,
                cache);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigHashGauge pulseConfigHashGauge(PulseDiagnostics diagnostics, MeterRegistry registry) {
        ConfigHashGauge gauge = new ConfigHashGauge(registry, ConfigHasher.hash(diagnostics.effectiveConfig()));
        gauge.register();
        return gauge;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pulse.banner", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PulseStartupBanner pulseStartupBanner(
            PulseDiagnostics.AllProperties properties,
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
    public HealthIndicator pulseJobsHealthIndicator(JobRegistry registry, JobsProperties properties) {
        return new JobsHealthIndicator(registry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "pulse.async",
            name = "scheduled-propagation-enabled",
            havingValue = "true",
            matchIfMissing = true)
    public PulseSchedulingConfigurer pulseSchedulingConfigurer(
            org.springframework.scheduling.TaskScheduler scheduler,
            JobsProperties jobs,
            ObjectProvider<JobRegistry> jobRegistry,
            ObjectProvider<MeterRegistry> meterRegistry) {
        java.util.function.UnaryOperator<org.springframework.scheduling.TaskScheduler> wrapper;
        if (jobs.enabled()) {
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
            ObjectProvider<OpenTelemetrySdk> sdk, ShutdownProperties properties) {
        return new PulseOtelShutdownLifecycle(sdk.getIfAvailable(), properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(jakarta.servlet.Filter.class)
    @ConditionalOnProperty(
            prefix = "pulse.shutdown.drain",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public InflightRequestCounter pulseInflightRequestCounter(MeterRegistry registry) {
        return new InflightRequestCounter(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(jakarta.servlet.Filter.class)
    @ConditionalOnProperty(
            prefix = "pulse.shutdown.drain",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public org.springframework.boot.web.servlet.FilterRegistrationBean<InflightRequestCounter>
            pulseInflightRequestCounterRegistration(InflightRequestCounter filter) {
        var reg = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        reg.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(jakarta.servlet.Filter.class)
    @ConditionalOnProperty(
            prefix = "pulse.shutdown.drain",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public PulseDrainObservabilityLifecycle pulseDrainObservabilityLifecycle(
            InflightRequestCounter counter, ShutdownProperties properties, MeterRegistry registry) {
        return new PulseDrainObservabilityLifecycle(counter, properties.drain(), registry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(OpenTelemetrySdk.class)
    @ConditionalOnProperty(
            prefix = "pulse.health",
            name = "otel-exporter-enabled",
            havingValue = "true",
            matchIfMissing = true)
    public static OtelExporterHealthRegistrar pulseOtelExporterHealthRegistrar() {
        return new OtelExporterHealthRegistrar();
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
            OtelExporterHealthRegistrar registrar, OtelExporterHealthProperties properties) {
        return new OtelExporterHealthIndicator(registrar::exporters, properties);
    }
}
