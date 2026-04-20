package io.github.arun0009.pulse.container;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Container memory observability — fills the JVM-vs-cgroup blind spot that bites every team
 * that has ever Googled "OOMKilled but heap looks fine." Pulse reads cgroup v1/v2
 * {@code memory.usage}/{@code memory.max} and exposes RSS, limit, headroom, and OOM-kill
 * meters.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.container-memory")
public record ContainerMemoryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean healthIndicatorEnabled,

        @DefaultValue("0.10") @DecimalMin("0.0") @DecimalMax("1.0") double headroomCriticalRatio,

        @DefaultValue("/sys/fs/cgroup") @NotBlank String cgroupRoot) {}
