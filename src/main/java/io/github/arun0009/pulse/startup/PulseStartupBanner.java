package io.github.arun0009.pulse.startup;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;

/**
 * Logs a single, scannable banner when Pulse finishes wiring. The intent is to make the README's
 * "verify in 90 seconds" claim true: a glance at the boot log tells you what is on, what is off,
 * and where to look next.
 */
public class PulseStartupBanner implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger("pulse.startup");

    private final PulseProperties properties;
    private final Environment env;
    private final String serviceName;

    public PulseStartupBanner(PulseProperties properties, Environment env, String serviceName) {
        this.properties = properties;
        this.env = env;
        this.serviceName = serviceName;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.banner().enabled()) return;

        String version = versionOrDev();
        String profiles = String.join(",", env.getActiveProfiles());
        if (profiles.isEmpty()) profiles = "default";

        String banner = """

────────────────────────────────────────────────────────────────────────────────
  PULSE %s active   service=%s   profiles=%s
────────────────────────────────────────────────────────────────────────────────
  TraceGuard            %s   fail-on-missing=%s
  Sampling              parentBased(traceIdRatio=%s)
  CardinalityFirewall   %s   max=%d/meter   overflow='%s'
  TimeoutBudget         %s   default=%s   max=%s   header=%s
  WideEvents            %s   counter='%s'
  AsyncPropagation      %s   pool=%d-%d
  PIIMasking            %s
  Endpoint              GET /actuator/pulse
────────────────────────────────────────────────────────────────────────────────
""".formatted(
                        version,
                        serviceName,
                        profiles,
                        onOff(properties.traceGuard().enabled()),
                        properties.traceGuard().failOnMissing(),
                        properties.sampling().probability(),
                        onOff(properties.cardinality().enabled()),
                        properties.cardinality().maxTagValuesPerMeter(),
                        properties.cardinality().overflowValue(),
                        onOff(properties.timeoutBudget().enabled()),
                        properties.timeoutBudget().defaultBudget(),
                        properties.timeoutBudget().maximumBudget(),
                        properties.timeoutBudget().inboundHeader(),
                        onOff(properties.wideEvents().enabled()),
                        properties.wideEvents().counterName(),
                        onOff(properties.async().propagationEnabled()),
                        properties.async().corePoolSize(),
                        properties.async().maxPoolSize(),
                        onOff(properties.logging().piiMaskingEnabled()));

        log.info(banner);
    }

    private static String onOff(boolean v) {
        return v ? "ON " : "OFF";
    }

    private String versionOrDev() {
        String v = getClass().getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
