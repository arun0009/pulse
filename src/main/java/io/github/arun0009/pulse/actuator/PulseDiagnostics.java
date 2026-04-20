package io.github.arun0009.pulse.actuator;

import io.github.arun0009.pulse.async.AsyncProperties;
import io.github.arun0009.pulse.cache.CacheProperties;
import io.github.arun0009.pulse.container.ContainerMemoryProperties;
import io.github.arun0009.pulse.core.ContextProperties;
import io.github.arun0009.pulse.core.TraceGuardProperties;
import io.github.arun0009.pulse.db.DbProperties;
import io.github.arun0009.pulse.dependencies.DependenciesProperties;
import io.github.arun0009.pulse.enforcement.PulseEnforcementMode;
import io.github.arun0009.pulse.events.WideEventsProperties;
import io.github.arun0009.pulse.exception.ExceptionHandlerProperties;
import io.github.arun0009.pulse.guardrails.CardinalityFirewall;
import io.github.arun0009.pulse.guardrails.CardinalityProperties;
import io.github.arun0009.pulse.guardrails.SamplingProperties;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetProperties;
import io.github.arun0009.pulse.health.OtelExporterHealthProperties;
import io.github.arun0009.pulse.jobs.JobRegistry;
import io.github.arun0009.pulse.jobs.JobsProperties;
import io.github.arun0009.pulse.logging.LoggingProperties;
import io.github.arun0009.pulse.logging.ResourceAttributeResolver;
import io.github.arun0009.pulse.metrics.HistogramsProperties;
import io.github.arun0009.pulse.openfeature.OpenFeatureProperties;
import io.github.arun0009.pulse.priority.PriorityProperties;
import io.github.arun0009.pulse.profiling.ProfilingProperties;
import io.github.arun0009.pulse.propagation.KafkaPropagationContext;
import io.github.arun0009.pulse.propagation.KafkaPropagationProperties;
import io.github.arun0009.pulse.resilience.ResilienceProperties;
import io.github.arun0009.pulse.resilience.RetryProperties;
import io.github.arun0009.pulse.shutdown.ShutdownProperties;
import io.github.arun0009.pulse.slo.SloProjector;
import io.github.arun0009.pulse.slo.SloProperties;
import io.github.arun0009.pulse.startup.BannerProperties;
import io.github.arun0009.pulse.tenant.TenantProperties;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of every Pulse subsystem and whether it is on/off, with the resolved configuration.
 * Backs {@link PulseEndpoint} so operators can answer "what is Pulse actually doing on this
 * instance?" without grepping logs.
 */
public final class PulseDiagnostics {

    /**
     * Aggregates every {@code *Properties} record so {@link PulseDiagnostics} and
     * {@link io.github.arun0009.pulse.startup.PulseStartupBanner} can consume them through a
     * single injectable bundle instead of ballooning constructor arities. The record is
     * assembled by {@code PulseAutoConfiguration#pulseAllProperties}.
     */
    public record AllProperties(
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
            CacheProperties cache) {}

    private final AllProperties p;
    private final String serviceName;
    private final String environment;
    private final String version;
    private final double samplingProbability;
    private final @Nullable CardinalityFirewall cardinalityFirewall;
    private final @Nullable SloProjector sloProjector;
    private final @Nullable JobRegistry jobRegistry;
    private final @Nullable PulseEnforcementMode enforcementMode;
    private final @Nullable ResourceAttributeResolver resourceAttributeResolver;

    /**
     * Single primary constructor. {@code samplingProbability} is sourced from
     * {@code management.tracing.sampling.probability} — Pulse defers to Spring Boot's standard
     * head-sampling property rather than shadowing it with a {@code pulse.*} alias. Tests that do
     * not need every runtime projection should pass {@code null} for the optional fields and
     * {@code 1.0} for the sampling probability.
     */
    public PulseDiagnostics(
            AllProperties p,
            String serviceName,
            String environment,
            String version,
            double samplingProbability,
            @Nullable CardinalityFirewall cardinalityFirewall,
            @Nullable SloProjector sloProjector,
            @Nullable JobRegistry jobRegistry,
            @Nullable PulseEnforcementMode enforcementMode,
            @Nullable ResourceAttributeResolver resourceAttributeResolver) {
        this.p = p;
        this.serviceName = serviceName;
        this.environment = environment;
        this.version = version;
        this.samplingProbability = samplingProbability;
        this.cardinalityFirewall = cardinalityFirewall;
        this.sloProjector = sloProjector;
        this.jobRegistry = jobRegistry;
        this.enforcementMode = enforcementMode;
        this.resourceAttributeResolver = resourceAttributeResolver;
    }

    /**
     * Returns the head sampling probability sourced from {@code management.tracing.sampling.probability}.
     *
     * @return the head sampling probability in {@code [0.0, 1.0]}.
     */
    public double samplingProbability() {
        return samplingProbability;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("pulse.version", version);
        root.put("service", serviceName);
        root.put("environment", environment);
        root.put(
                "mode",
                enforcementMode == null ? "ENFORCING" : enforcementMode.get().name());
        root.put("subsystems", subsystems());
        root.put("effectiveConfig", effectiveConfig());
        root.put("runtime", runtime());
        return root;
    }

    /**
     * Returns the live enforcement mode bean, or {@code null} when none was wired. Public so
     * {@link PulseEndpoint} can mutate it via the {@code POST /actuator/pulse/enforcement} write
     * operation.
     */
    public @Nullable PulseEnforcementMode enforcementMode() {
        return enforcementMode;
    }

    public Map<String, Object> effectiveConfig() {
        Map<String, Object> pulse = new LinkedHashMap<>();
        pulse.put("context", p.context());
        pulse.put("traceGuard", p.traceGuard());
        pulse.put("sampling", p.sampling());
        pulse.put("async", p.async());
        pulse.put("kafka", p.kafka());
        pulse.put("exceptionHandler", p.exceptionHandler());
        pulse.put("cardinality", p.cardinality());
        pulse.put("timeoutBudget", p.timeoutBudget());
        pulse.put("wideEvents", p.wideEvents());
        pulse.put("logging", p.logging());
        pulse.put("banner", p.banner());
        pulse.put("histograms", p.histograms());
        pulse.put("slo", p.slo());
        pulse.put("health", p.health());
        pulse.put("shutdown", p.shutdown());
        pulse.put("jobs", p.jobs());
        pulse.put("db", p.db());
        pulse.put("resilience", p.resilience());
        pulse.put("profiling", p.profiling());
        pulse.put("dependencies", p.dependencies());
        pulse.put("tenant", p.tenant());
        pulse.put("retry", p.retry());
        pulse.put("priority", p.priority());
        pulse.put("containerMemory", p.containerMemory());
        pulse.put("openFeature", p.openFeature());
        pulse.put("cache", p.cache());
        return Map.of("pulse", pulse);
    }

    public Map<String, Object> runtime() {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("cardinalityFirewall", cardinalityRuntime());
        runtime.put("slo", sloRuntime());
        runtime.put("jobs", jobsRuntime());
        runtime.put("resourceAttributes", resourceAttributesRuntime());
        return runtime;
    }

    /** Returns the live SLO compliance projection or an empty list when no projector is wired. */
    public List<SloProjector.SloStatus> sloProjection() {
        if (sloProjector == null) return List.of();
        return sloProjector.project();
    }

    private Map<String, Object> subsystems() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(
                "requestContext",
                entry(
                        p.context().enabled(),
                        Map.of(
                                "requestIdHeader", p.context().requestIdHeader(),
                                "userIdHeader", p.context().userIdHeader(),
                                "tenantIdHeader", p.context().tenantIdHeader(),
                                "idempotencyKeyHeader", p.context().idempotencyKeyHeader(),
                                "additionalHeaders", p.context().additionalHeaders())));
        map.put(
                "traceGuard",
                entry(
                        p.traceGuard().enabled(),
                        Map.of(
                                "failOnMissing", p.traceGuard().failOnMissing(),
                                "excludePathPrefixes", p.traceGuard().excludePathPrefixes())));
        map.put(
                "sampling",
                Map.of(
                        "probability",
                        samplingProbability,
                        "probabilitySource",
                        "management.tracing.sampling.probability",
                        "preferSamplingOnError",
                        p.sampling().preferSamplingOnError()));
        map.put(
                "async",
                entry(
                        p.async().enabled(),
                        Map.of(
                                "dedicatedExecutor", p.async().dedicatedExecutor(),
                                "scheduledPropagationEnabled", p.async().scheduledPropagationEnabled(),
                                "corePoolSize", p.async().corePoolSize(),
                                "maxPoolSize", p.async().maxPoolSize())));
        boolean kafkaConfigured = p.kafka().propagationEnabled();
        boolean kafkaWired = KafkaPropagationContext.initialized();
        Map<String, Object> kafkaDetails = new LinkedHashMap<>();
        kafkaDetails.put("classpathPresent", kafkaWired);
        kafkaDetails.put("consumerTimeLagEnabled", p.kafka().consumerTimeLagEnabled());
        kafkaDetails.put(
                "status",
                kafkaConfigured ? (kafkaWired ? "active" : "off (spring-kafka not on classpath)") : "disabled");
        map.put("kafka", entry(kafkaConfigured && kafkaWired, kafkaDetails));
        map.put("exceptionHandler", entry(p.exceptionHandler().enabled(), Map.of()));
        map.put(
                "cardinalityFirewall",
                entry(
                        p.cardinality().enabled(),
                        Map.of(
                                "maxTagValuesPerMeter", p.cardinality().maxTagValuesPerMeter(),
                                "overflowValue", p.cardinality().overflowValue(),
                                "meterPrefixesToProtect", p.cardinality().meterPrefixesToProtect(),
                                "exemptMeterPrefixes", p.cardinality().exemptMeterPrefixes())));
        map.put(
                "timeoutBudget",
                entry(
                        p.timeoutBudget().enabled(),
                        Map.of(
                                "inboundHeader", p.timeoutBudget().inboundHeader(),
                                "outboundHeader", p.timeoutBudget().outboundHeader(),
                                "defaultBudget",
                                        p.timeoutBudget().defaultBudget().toString(),
                                "maximumBudget",
                                        p.timeoutBudget().maximumBudget().toString(),
                                "safetyMargin", p.timeoutBudget().safetyMargin().toString(),
                                "minimumBudget",
                                        p.timeoutBudget().minimumBudget().toString())));
        map.put(
                "wideEvents",
                entry(
                        p.wideEvents().enabled(),
                        Map.of(
                                "counterEnabled", p.wideEvents().counterEnabled(),
                                "logEnabled", p.wideEvents().logEnabled(),
                                "counterName", p.wideEvents().counterName())));
        map.put("logging", Map.of("piiMaskingEnabled", p.logging().piiMaskingEnabled()));
        map.put(
                "histograms",
                entry(
                        p.histograms().enabled(),
                        Map.of(
                                "meterPrefixes", p.histograms().meterPrefixes(),
                                "sloBuckets",
                                        p.histograms().sloBuckets().stream()
                                                .map(Object::toString)
                                                .toList())));
        map.put(
                "slo",
                entry(
                        p.slo().enabled(),
                        Map.of(
                                "objectiveCount", p.slo().objectives().size(),
                                "objectives",
                                        p.slo().objectives().stream()
                                                .map(o -> o.name())
                                                .toList())));
        map.put(
                "jobs",
                entry(
                        p.jobs().enabled(),
                        Map.of(
                                "healthIndicatorEnabled", p.jobs().healthIndicatorEnabled(),
                                "failureGracePeriod",
                                        p.jobs().failureGracePeriod().toString())));
        map.put(
                "db",
                entry(
                        p.db().enabled(),
                        Map.of(
                                "nPlusOneThreshold", p.db().nPlusOneThreshold(),
                                "slowQueryThreshold",
                                        p.db().slowQueryThreshold().toString())));
        map.put("resilience", entry(p.resilience().enabled(), Map.of()));
        Map<String, Object> profilingDetails = new LinkedHashMap<>();
        profilingDetails.put(
                "pyroscopeUrl",
                p.profiling().pyroscopeUrl() == null ? "" : p.profiling().pyroscopeUrl());
        io.github.arun0009.pulse.profiling.PyroscopeDetector.Detection detection =
                io.github.arun0009.pulse.profiling.PyroscopeDetector.detect();
        profilingDetails.put("pyroscopeAgentDetected", detection.present());
        if (detection.present()) {
            profilingDetails.put(
                    "pyroscopeAgentApplication",
                    detection.applicationName() == null ? "" : detection.applicationName());
            profilingDetails.put(
                    "pyroscopeAgentServer", detection.serverAddress() == null ? "" : detection.serverAddress());
        }
        map.put("profiling", entry(p.profiling().enabled(), profilingDetails));
        Map<String, Object> dependenciesDetails = new LinkedHashMap<>();
        dependenciesDetails.put("knownHosts", p.dependencies().map().size());
        dependenciesDetails.put("defaultName", p.dependencies().defaultName());
        dependenciesDetails.put("fanOutWarnThreshold", p.dependencies().fanOutWarnThreshold());
        map.put("dependencies", entry(p.dependencies().enabled(), dependenciesDetails));
        Map<String, Object> tenantDetails = new LinkedHashMap<>();
        tenantDetails.put("headerEnabled", p.tenant().header().enabled());
        tenantDetails.put("headerName", p.tenant().header().name());
        tenantDetails.put("jwtEnabled", p.tenant().jwt().enabled());
        tenantDetails.put("jwtClaim", p.tenant().jwt().claim());
        tenantDetails.put("subdomainEnabled", p.tenant().subdomain().enabled());
        tenantDetails.put("maxTagCardinality", p.tenant().maxTagCardinality());
        tenantDetails.put("tagMeters", p.tenant().tagMeters());
        map.put("tenant", entry(p.tenant().enabled(), tenantDetails));
        map.put(
                "retry",
                entry(
                        p.retry().enabled(),
                        Map.of(
                                "headerName", p.retry().headerName(),
                                "amplificationThreshold", p.retry().amplificationThreshold())));
        map.put(
                "containerMemory",
                entry(
                        p.containerMemory().enabled(),
                        Map.of(
                                "healthIndicatorEnabled", p.containerMemory().healthIndicatorEnabled(),
                                "headroomCriticalRatio", p.containerMemory().headroomCriticalRatio(),
                                "cgroupRoot", p.containerMemory().cgroupRoot())));
        map.put(
                "priority",
                entry(
                        p.priority().enabled(),
                        Map.of(
                                "headerName", p.priority().headerName(),
                                "defaultPriority", p.priority().defaultPriority(),
                                "warnOnCriticalTimeoutExhaustion", p.priority().warnOnCriticalTimeoutExhaustion(),
                                "tagMeters", p.priority().tagMeters())));
        map.put("openFeature", entry(p.openFeature().enabled(), Map.of()));
        map.put(
                "cache",
                entry(
                        p.cache().caffeine().enabled(),
                        Map.of("caffeineEnabled", p.cache().caffeine().enabled())));
        return map;
    }

    private static Map<String, Object> entry(boolean enabled, Map<String, Object> details) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        if (!details.isEmpty()) {
            map.put("config", details);
        }
        return map;
    }

    private Map<String, Object> sloRuntime() {
        if (sloProjector == null) {
            return Map.of("wired", false);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("wired", true);
        root.put(
                "objectives",
                sloProjector.project().stream()
                        .map(s -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("name", s.name());
                            row.put("sli", s.sli());
                            row.put("target", s.target());
                            row.put("currentRatio", s.currentRatio());
                            row.put("sampleCount", s.sampleCount());
                            row.put("status", s.status());
                            return row;
                        })
                        .toList());
        return root;
    }

    private Map<String, Object> jobsRuntime() {
        if (jobRegistry == null) {
            return Map.of("wired", false);
        }
        Map<String, JobRegistry.JobSnapshot> jobs = jobRegistry.snapshot();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("wired", true);
        root.put("observedJobs", jobs.size());
        root.put(
                "jobs",
                jobs.entrySet().stream()
                        .map(e -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("name", e.getKey());
                            row.put("successCount", e.getValue().successCount());
                            row.put("failureCount", e.getValue().failureCount());
                            row.put(
                                    "lastSuccessAt",
                                    e.getValue().lastSuccessAt() != null
                                            ? e.getValue().lastSuccessAt().toString()
                                            : null);
                            row.put(
                                    "lastFailureAt",
                                    e.getValue().lastFailureAt() != null
                                            ? e.getValue().lastFailureAt().toString()
                                            : null);
                            row.put("lastFailureCause", e.getValue().lastFailureCause());
                            row.put(
                                    "lastDurationMs",
                                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                            e.getValue().lastDurationNanos()));
                            return row;
                        })
                        .toList());
        return root;
    }

    private Map<String, Object> cardinalityRuntime() {
        if (cardinalityFirewall == null) {
            return Map.of("wired", false);
        }
        return Map.of(
                "wired", true,
                "totalOverflowRewrites", cardinalityFirewall.totalOverflowRewrites(),
                "topOffenders", cardinalityFirewall.topOverflowingTags(10));
    }

    /**
     * Snapshot of {@link ResourceAttributeResolver#resolveAll()} — the same map Pulse seeds as
     * JVM system properties at {@code EnvironmentPostProcessor} time for log layouts, exposed here
     * so operators can verify host/cloud/k8s detection without grepping startup logs. When no
     * resolver bean is wired (tests or stripped-down contexts), returns {@code wired=false}.
     */
    private Map<String, Object> resourceAttributesRuntime() {
        if (resourceAttributeResolver == null) {
            return Map.of("wired", false);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("wired", true);
        root.put("resolverClass", resourceAttributeResolver.getClass().getName());
        root.put("resolved", resourceAttributeResolver.resolveAll());
        return root;
    }
}
