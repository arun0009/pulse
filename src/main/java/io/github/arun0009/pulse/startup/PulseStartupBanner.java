package io.github.arun0009.pulse.startup;

import io.github.arun0009.pulse.actuator.PulseDiagnostics;
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

    private final PulseDiagnostics.AllProperties p;
    private final Environment env;
    private final String serviceName;
    private final double samplingProbability;

    public PulseStartupBanner(PulseDiagnostics.AllProperties p, Environment env, String serviceName) {
        this(p, env, serviceName, env.getProperty("management.tracing.sampling.probability", Double.class, 1.0));
    }

    public PulseStartupBanner(
            PulseDiagnostics.AllProperties p, Environment env, String serviceName, double samplingProbability) {
        this.p = p;
        this.env = env;
        this.serviceName = serviceName;
        this.samplingProbability = samplingProbability;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!p.banner().enabled()) return;

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
                        onOff(p.traceGuard().enabled()),
                        p.traceGuard().failOnMissing(),
                        samplingProbability,
                        onOff(p.cardinality().enabled()),
                        p.cardinality().maxTagValuesPerMeter(),
                        p.cardinality().overflowValue(),
                        onOff(p.timeoutBudget().enabled()),
                        p.timeoutBudget().defaultBudget(),
                        p.timeoutBudget().maximumBudget(),
                        p.timeoutBudget().inboundHeader(),
                        onOff(p.wideEvents().enabled()),
                        p.wideEvents().counterName(),
                        onOff(p.async().enabled()),
                        p.async().corePoolSize(),
                        p.async().maxPoolSize(),
                        onOff(p.logging().piiMaskingEnabled()));

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
