package io.github.arun0009.pulse.container.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.container.CgroupMemoryReader;
import io.github.arun0009.pulse.container.ContainerMemoryHealthIndicator;
import io.github.arun0009.pulse.container.ContainerMemoryMetrics;
import io.github.arun0009.pulse.container.ContainerMemoryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * Wires the container memory subsystem.
 *
 * <p>Both the metrics registrar and the health indicator are bean-registered up-front but
 * each silently no-ops when no cgroup is visible on the host. That keeps the auto-config
 * platform-portable: the same starter runs on a developer Mac, a CI Linux runner without
 * cgroups, and a production EKS pod with cgroup v2.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnProperty(prefix = "pulse.container-memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PulseContainerMemoryConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CgroupMemoryReader pulseCgroupMemoryReader(ContainerMemoryProperties properties) {
        return new CgroupMemoryReader(properties.cgroupRoot());
    }

    @Bean
    @ConditionalOnMissingBean
    public ContainerMemoryMetrics pulseContainerMemoryMetrics(CgroupMemoryReader reader, MeterRegistry registry) {
        ContainerMemoryMetrics metrics = new ContainerMemoryMetrics(reader, registry);
        metrics.register();
        return metrics;
    }

    @Bean
    @ConditionalOnMissingBean(name = "pulseContainerMemoryHealthIndicator")
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnProperty(
            prefix = "pulse.container-memory",
            name = "health-indicator-enabled",
            havingValue = "true",
            matchIfMissing = true)
    public HealthIndicator pulseContainerMemoryHealthIndicator(
            ContainerMemoryMetrics metrics, ContainerMemoryProperties properties) {
        return new ContainerMemoryHealthIndicator(metrics, properties);
    }
}
