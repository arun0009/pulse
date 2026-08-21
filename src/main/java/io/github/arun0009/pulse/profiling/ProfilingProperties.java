package io.github.arun0009.pulse.profiling;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Continuous-profiling correlation. Opt-in via {@code pulse.profiling.enabled=true}. When on, Pulse stamps {@code profile.id}
 * and {@code pyroscope.profile_id} attributes on every span using the trace id.
 *
 * <p>Pulse never bundles or starts a profiler. {@link io.github.arun0009.pulse.profiling.PyroscopeDetector}
 * detects the {@code -javaagent:pyroscope.jar} the operator already injected.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.profiling")
public record ProfilingProperties(
        @DefaultValue("false") boolean enabled, @Nullable String pyroscopeUrl) {}
