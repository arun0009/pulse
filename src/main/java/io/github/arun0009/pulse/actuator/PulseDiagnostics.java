package io.github.arun0009.pulse.actuator;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.github.arun0009.pulse.guardrails.CardinalityFirewall;
import io.github.arun0009.pulse.propagation.KafkaPropagationContext;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
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

    public PulseDiagnostics(
            PulseProperties properties,
            String serviceName,
            String environment,
            String version,
            @Nullable CardinalityFirewall cardinalityFirewall) {
        this.properties = properties;
        this.serviceName = serviceName;
        this.environment = environment;
        this.version = version;
        this.cardinalityFirewall = cardinalityFirewall;
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
        pulse.put("audit", properties.audit());
        pulse.put("cardinality", properties.cardinality());
        pulse.put("timeoutBudget", properties.timeoutBudget());
        pulse.put("wideEvents", properties.wideEvents());
        pulse.put("logging", properties.logging());
        pulse.put("banner", properties.banner());
        pulse.put("histograms", properties.histograms());
        pulse.put("slo", properties.slo());
        return Map.of("pulse", pulse);
    }

    public Map<String, Object> runtime() {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("cardinalityFirewall", cardinalityRuntime());
        return runtime;
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
                                "autoConfigureExecutor", properties.async().autoConfigureExecutor(),
                                "corePoolSize", properties.async().corePoolSize(),
                                "maxPoolSize", properties.async().maxPoolSize())));
        boolean kafkaConfigured = properties.kafka().propagationEnabled();
        boolean kafkaWired = KafkaPropagationContext.initialized();
        Map<String, Object> kafkaDetails = new LinkedHashMap<>();
        kafkaDetails.put("classpathPresent", kafkaWired);
        kafkaDetails.put(
                "status",
                kafkaConfigured ? (kafkaWired ? "active" : "off (spring-kafka not on classpath)") : "disabled");
        map.put("kafka", entry(kafkaConfigured && kafkaWired, kafkaDetails));
        map.put("exceptionHandler", entry(properties.exceptionHandler().enabled(), Map.of()));
        map.put("audit", entry(properties.audit().enabled(), Map.of()));
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
