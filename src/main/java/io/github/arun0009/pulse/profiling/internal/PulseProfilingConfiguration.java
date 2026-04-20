package io.github.arun0009.pulse.profiling.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.profiling.ProfilingProperties;
import io.github.arun0009.pulse.profiling.ProfilingRegistration;
import io.github.arun0009.pulse.profiling.PulseProfilingSpanProcessor;
import io.github.arun0009.pulse.profiling.PyroscopeDetector;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Wires Pulse's profiling-correlation subsystem.
 *
 * <p>The configuration produces two beans (both gated on {@code pulse.profiling.enabled=true},
 * default {@code true}):
 *
 * <ul>
 *   <li>A {@link PulseProfilingSpanProcessor} exposed as a {@link SpanProcessor} bean. Spring
 *       Boot's tracing auto-configuration collects every {@code SpanProcessor} bean from the
 *       context and composes them into the SDK's processor pipeline, so simply publishing the
 *       bean is enough to have it stamp {@code profile.id}, {@code pyroscope.profile_id} and
 *       (root-only) {@code pulse.profile.url} on every span. No reflection or builder
 *       interception is required.
 *   <li>A {@link ProfilingRegistration} that snapshots the result of {@link PyroscopeDetector}
 *       at startup. The snapshot is surfaced through {@code /actuator/pulse} so operators can
 *       confirm "yes, the profile-trace link is wired" without grepping log output.
 * </ul>
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnClass(SpanProcessor.class)
@ConditionalOnProperty(prefix = "pulse.profiling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PulseProfilingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PulseProfilingConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(PulseProfilingSpanProcessor.class)
    public SpanProcessor pulseProfilingSpanProcessor(
            ProfilingProperties profiling, @Value("${spring.application.name:unknown-service}") String serviceName) {
        return new PulseProfilingSpanProcessor(serviceName, profiling.pyroscopeUrl());
    }

    @Bean
    @ConditionalOnMissingBean
    public ProfilingRegistration pulseProfilingRegistration() {
        PyroscopeDetector.Detection detection = PyroscopeDetector.detect();
        if (detection.present()) {
            log.info(
                    "Pulse: detected Pyroscope agent (application={}, server={}). Span attributes"
                            + " profile.id and pyroscope.profile_id are being stamped on every span;"
                            + " configure pulse.profiling.pyroscope-url to add a root-span deep link.",
                    detection.applicationName() == null ? "<unset>" : detection.applicationName(),
                    detection.serverAddress() == null ? "<unset>" : detection.serverAddress());
        }
        return new ProfilingRegistration(detection);
    }
}
