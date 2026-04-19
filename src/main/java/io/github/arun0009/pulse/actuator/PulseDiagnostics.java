package io.github.arun0009.pulse.actuator;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.github.arun0009.pulse.guardrails.CardinalityFirewall;
import io.github.arun0009.pulse.jobs.JobRegistry;
import io.github.arun0009.pulse.propagation.KafkaPropagationContext;
import io.github.arun0009.pulse.slo.SloProjector;
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

    private final PulseProperties properties;
    private final String serviceName;
    private final String environment;
    private final String version;
    private final @Nullable CardinalityFirewall cardinalityFirewall;
    private final @Nullable SloProjector sloProjector;
    private final @Nullable JobRegistry jobRegistry;

    public PulseDiagnostics(
            PulseProperties properties,
            String serviceName,
            String environment,
            String version,
            @Nullable CardinalityFirewall cardinalityFirewall,
            @Nullable SloProjector sloProjector,
            @Nullable JobRegistry jobRegistry) {
        this.properties = properties;
        this.serviceName = serviceName;
        this.environment = environment;
        this.version = version;
        this.cardinalityFirewall = cardinalityFirewall;
        this.sloProjector = sloProjector;
        this.jobRegistry = jobRegistry;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("pulse.version", version);
        root.put("service", serviceName);
        root.put("environment", environment);
        root.put("subsystems", subsystems());
        root.put("effectiveConfig", effectiveConfig());
        root.put("runtime", runtime());
        return root;
    }

    public Map<String, Object> effectiveConfig() {
        Map<String, Object> pulse = new LinkedHashMap<>();
        pulse.put("context", properties.context());
        pulse.put("traceGuard", properties.traceGuard());
        pulse.put("sampling", properties.sampling());
        pulse.put("async", properties.async());
        pulse.put("kafka", properties.kafka());
        pulse.put("exceptionHandler", properties.exceptionHandler());
        pulse.put("cardinality", properties.cardinality());
        pulse.put("timeoutBudget", properties.timeoutBudget());
        pulse.put("wideEvents", properties.wideEvents());
        pulse.put("logging", properties.logging());
        pulse.put("banner", properties.banner());
        pulse.put("histograms", properties.histograms());
        pulse.put("slo", properties.slo());
        pulse.put("health", properties.health());
        pulse.put("shutdown", properties.shutdown());
        pulse.put("jobs", properties.jobs());
        pulse.put("db", properties.db());
        pulse.put("resilience", properties.resilience());
        pulse.put("profiling", properties.profiling());
        pulse.put("dependencies", properties.dependencies());
        pulse.put("tenant", properties.tenant());
        pulse.put("retry", properties.retry());
        pulse.put("priority", properties.priority());
        pulse.put("containerMemory", properties.containerMemory());
        pulse.put("openFeature", properties.openFeature());
        pulse.put("cache", properties.cache());
        return Map.of("pulse", pulse);
    }

    public Map<String, Object> runtime() {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("cardinalityFirewall", cardinalityRuntime());
        runtime.put("slo", sloRuntime());
        runtime.put("jobs", jobsRuntime());
        return runtime;
    }

    /** Returns the live SLO compliance projection or {@code null} when no projector is wired. */
    public List<SloProjector.SloStatus> sloProjection() {
        if (sloProjector == null) return List.of();
        return sloProjector.project();
    }

    private Map<String, Object> subsystems() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(
                "requestContext",
                entry(
                        properties.context().enabled(),
                        Map.of(
                                "requestIdHeader", properties.context().requestIdHeader(),
                                "userIdHeader", properties.context().userIdHeader(),
                                "tenantIdHeader", properties.context().tenantIdHeader(),
                                "idempotencyKeyHeader", properties.context().idempotencyKeyHeader(),
                                "additionalHeaders", properties.context().additionalHeaders())));
        map.put(
                "traceGuard",
                entry(
                        properties.traceGuard().enabled(),
                        Map.of(
                                "failOnMissing", properties.traceGuard().failOnMissing(),
                                "excludePathPrefixes", properties.traceGuard().excludePathPrefixes())));
        map.put("sampling", Map.of("probability", properties.sampling().probability()));
        map.put(
                "async",
                entry(
                        properties.async().propagationEnabled(),
                        Map.of(
                                "dedicatedExecutor", properties.async().dedicatedExecutor(),
                                "scheduledPropagationEnabled",
                                        properties.async().scheduledPropagationEnabled(),
                                "corePoolSize", properties.async().corePoolSize(),
                                "maxPoolSize", properties.async().maxPoolSize())));
        boolean kafkaConfigured = properties.kafka().propagationEnabled();
        boolean kafkaWired = KafkaPropagationContext.initialized();
        Map<String, Object> kafkaDetails = new LinkedHashMap<>();
        kafkaDetails.put("classpathPresent", kafkaWired);
        kafkaDetails.put("consumerTimeLagEnabled", properties.kafka().consumerTimeLagEnabled());
        kafkaDetails.put(
                "status",
                kafkaConfigured ? (kafkaWired ? "active" : "off (spring-kafka not on classpath)") : "disabled");
        map.put("kafka", entry(kafkaConfigured && kafkaWired, kafkaDetails));
        map.put("exceptionHandler", entry(properties.exceptionHandler().enabled(), Map.of()));
        map.put(
                "cardinalityFirewall",
                entry(
                        properties.cardinality().enabled(),
                        Map.of(
                                "maxTagValuesPerMeter", properties.cardinality().maxTagValuesPerMeter(),
                                "overflowValue", properties.cardinality().overflowValue(),
                                "meterPrefixesToProtect",
                                        properties.cardinality().meterPrefixesToProtect(),
                                "exemptMeterPrefixes", properties.cardinality().exemptMeterPrefixes())));
        map.put(
                "timeoutBudget",
                entry(
                        properties.timeoutBudget().enabled(),
                        Map.of(
                                "inboundHeader", properties.timeoutBudget().inboundHeader(),
                                "outboundHeader", properties.timeoutBudget().outboundHeader(),
                                "defaultBudget",
                                        properties
                                                .timeoutBudget()
                                                .defaultBudget()
                                                .toString(),
                                "maximumBudget",
                                        properties
                                                .timeoutBudget()
                                                .maximumBudget()
                                                .toString(),
                                "safetyMargin",
                                        properties
                                                .timeoutBudget()
                                                .safetyMargin()
                                                .toString(),
                                "minimumBudget",
                                        properties
                                                .timeoutBudget()
                                                .minimumBudget()
                                                .toString())));
        map.put(
                "wideEvents",
                entry(
                        properties.wideEvents().enabled(),
                        Map.of(
                                "counterEnabled", properties.wideEvents().counterEnabled(),
                                "logEnabled", properties.wideEvents().logEnabled(),
                                "counterName", properties.wideEvents().counterName())));
        map.put("logging", Map.of("piiMaskingEnabled", properties.logging().piiMaskingEnabled()));
        map.put(
                "histograms",
                entry(
                        properties.histograms().enabled(),
                        Map.of(
                                "meterPrefixes", properties.histograms().meterPrefixes(),
                                "sloBuckets",
                                        properties.histograms().sloBuckets().stream()
                                                .map(Object::toString)
                                                .toList())));
        map.put(
                "slo",
                entry(
                        properties.slo().enabled(),
                        Map.of(
                                "objectiveCount", properties.slo().objectives().size(),
                                "objectives",
                                        properties.slo().objectives().stream()
                                                .map(o -> o.name())
                                                .toList())));
        map.put(
                "jobs",
                entry(
                        properties.jobs().enabled(),
                        Map.of(
                                "healthIndicatorEnabled", properties.jobs().healthIndicatorEnabled(),
                                "failureGracePeriod",
                                        properties.jobs().failureGracePeriod().toString())));
        map.put(
                "db",
                entry(
                        properties.db().enabled(),
                        Map.of(
                                "nPlusOneThreshold", properties.db().nPlusOneThreshold(),
                                "slowQueryThreshold",
                                        properties.db().slowQueryThreshold().toString())));
        map.put("resilience", entry(properties.resilience().enabled(), Map.of()));
        Map<String, Object> profilingDetails = new LinkedHashMap<>();
        profilingDetails.put(
                "pyroscopeUrl",
                properties.profiling().pyroscopeUrl() == null
                        ? ""
                        : properties.profiling().pyroscopeUrl());
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
        map.put("profiling", entry(properties.profiling().enabled(), profilingDetails));
        Map<String, Object> dependenciesDetails = new LinkedHashMap<>();
        dependenciesDetails.put("knownHosts", properties.dependencies().map().size());
        dependenciesDetails.put("defaultName", properties.dependencies().defaultName());
        dependenciesDetails.put("fanOutWarnThreshold", properties.dependencies().fanOutWarnThreshold());
        map.put("dependencies", entry(properties.dependencies().enabled(), dependenciesDetails));
        Map<String, Object> tenantDetails = new LinkedHashMap<>();
        tenantDetails.put("headerEnabled", properties.tenant().header().enabled());
        tenantDetails.put("headerName", properties.tenant().header().name());
        tenantDetails.put("jwtEnabled", properties.tenant().jwt().enabled());
        tenantDetails.put("jwtClaim", properties.tenant().jwt().claim());
        tenantDetails.put("subdomainEnabled", properties.tenant().subdomain().enabled());
        tenantDetails.put("maxTagCardinality", properties.tenant().maxTagCardinality());
        tenantDetails.put("tagMeters", properties.tenant().tagMeters());
        map.put("tenant", entry(properties.tenant().enabled(), tenantDetails));
        map.put(
                "retry",
                entry(
                        properties.retry().enabled(),
                        Map.of(
                                "headerName", properties.retry().headerName(),
                                "amplificationThreshold", properties.retry().amplificationThreshold())));
        map.put(
                "containerMemory",
                entry(
                        properties.containerMemory().enabled(),
                        Map.of(
                                "healthIndicatorEnabled",
                                        properties.containerMemory().healthIndicatorEnabled(),
                                "headroomCriticalRatio",
                                        properties.containerMemory().headroomCriticalRatio(),
                                "cgroupRoot", properties.containerMemory().cgroupRoot())));
        map.put(
                "priority",
                entry(
                        properties.priority().enabled(),
                        Map.of(
                                "headerName", properties.priority().headerName(),
                                "defaultPriority", properties.priority().defaultPriority(),
                                "warnOnCriticalTimeoutExhaustion",
                                        properties.priority().warnOnCriticalTimeoutExhaustion(),
                                "tagMeters", properties.priority().tagMeters())));
        map.put("openFeature", entry(properties.openFeature().enabled(), Map.of()));
        map.put(
                "cache",
                entry(
                        properties.cache().caffeine().enabled(),
                        Map.of("caffeineEnabled", properties.cache().caffeine().enabled())));
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
}
