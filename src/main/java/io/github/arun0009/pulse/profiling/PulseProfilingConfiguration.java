package io.github.arun0009.pulse.profiling;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Pulse's profiling-correlation subsystem.
 *
 * <p>The configuration produces two beans (both gated on {@code pulse.profiling.enabled=true},
 * default {@code true}):
 *
 * <ul>
 *   <li>A {@link PulseProfilingSpanProcessor} that stamps {@code profile.id},
 *       {@code pyroscope.profile_id}, and (root-only) {@code pulse.profile.url} on every span.
 *       Always registered when an OTel SDK is present — the attributes are cheap and useful
 *       even when no profiler is actually running, since they let trace UIs render the link
 *       template.
 *   <li>A startup-time log line via {@link PyroscopeDetector} that confirms whether the
 *       Pyroscope agent is loaded. The detection result is surfaced to {@code /actuator/pulse}
 *       so operators can confirm "yes, the profile-trace link is wired" without reading log
 *       output.
 * </ul>
 *
 * <p>The span processor is registered onto the running {@link OpenTelemetrySdk} via Spring
 * Boot's standard SDK auto-config — see {@link PulseProfilingConfiguration#registerWithSdk}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OpenTelemetrySdk.class)
@ConditionalOnProperty(prefix = "pulse.profiling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PulseProfilingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PulseProfilingConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public PulseProfilingSpanProcessor pulseProfilingSpanProcessor(
            PulseProperties properties, @Value("${spring.application.name:unknown-service}") String serviceName) {
        return new PulseProfilingSpanProcessor(
                serviceName, properties.profiling().pyroscopeUrl());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(OpenTelemetrySdk.class)
    public Object pulseProfilingRegistrar(OpenTelemetrySdk sdk, SpanProcessor pulseProfilingSpanProcessor) {
        return registerWithSdk(sdk, pulseProfilingSpanProcessor);
    }

    /**
     * Adds the span processor to the running tracer provider. Returns a placeholder bean so
     * Spring keeps a reference (preventing premature GC of the registration object).
     *
     * <p>We register at <em>runtime</em> rather than via the SDK builder because Spring Boot's
     * OpenTelemetry auto-configuration constructs the SDK in a builder we cannot easily
     * intercept without subclassing the OpenTelemetry auto-configuration. Adding to the
     * already-built SdkTracerProvider is a supported public API on Spring Boot's exposed SDK
     * bean and keeps Pulse's wiring decoupled from internal Boot APIs.
     */
    static Object registerWithSdk(OpenTelemetrySdk sdk, SpanProcessor processor) {
        try {
            // SdkTracerProvider does not expose a public addSpanProcessor on all versions;
            // when present, use it. When absent, log + return — Pulse never throws at startup.
            sdk.getSdkTracerProvider()
                    .getClass()
                    .getMethod("addSpanProcessor", SpanProcessor.class)
                    .invoke(sdk.getSdkTracerProvider(), processor);
        } catch (NoSuchMethodException nsme) {
            log.warn(
                    "SdkTracerProvider.addSpanProcessor not available on {} — profile-trace correlation"
                            + " attributes will not be stamped on spans. Upgrade opentelemetry-sdk-trace to"
                            + " the version your Boot starter declares.",
                    sdk.getSdkTracerProvider().getClass().getName());
        } catch (ReflectiveOperationException roe) {
            log.warn("Failed to register Pulse profiling SpanProcessor", roe);
        }
        // Detection log fires once at registration time so the startup banner is honest.
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

    /** Marker bean kept on the context so Spring doesn't GC the registration object. */
    public record ProfilingRegistration(PyroscopeDetector.Detection detection) {}
}
