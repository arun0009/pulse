package io.github.arun0009.pulse.autoconfigure;

import io.github.arun0009.pulse.async.AsyncProperties;
import io.github.arun0009.pulse.container.ContainerMemoryProperties;
import io.github.arun0009.pulse.core.ContextProperties;
import io.github.arun0009.pulse.guardrails.CardinalityProperties;
import io.github.arun0009.pulse.guardrails.SamplingProperties;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies that each per-feature {@code @ConfigurationProperties} record fails fast at startup when
 * an out-of-range value is supplied. Without this gate, a typo like
 * {@code pulse.cardinality.max-tag-values-per-meter: -5} would silently mis-configure the firewall;
 * with {@link org.springframework.validation.annotation.Validated} on each record root and JSR-380
 * constraints on individual fields, Spring Boot rejects the binding before the application context
 * starts.
 *
 * <p>The head sampling rate ({@code management.tracing.sampling.probability}) is owned by Spring
 * Boot's tracing auto-configuration, not by Pulse, so its validation is covered by Boot itself.
 */
class PulsePropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(EnableProps.class);

    @Test
    void defaults_pass_validation() {
        runner.run(ctx -> Assertions.assertThat(ctx)
                .hasNotFailed()
                .hasSingleBean(SamplingProperties.class)
                .hasSingleBean(CardinalityProperties.class)
                .hasSingleBean(ContextProperties.class)
                .hasSingleBean(AsyncProperties.class)
                .hasSingleBean(ContainerMemoryProperties.class));
    }

    @Test
    void rejects_negative_max_tag_values() {
        runner.withPropertyValues("pulse.cardinality.max-tag-values-per-meter=-5")
                .run(ctx -> Assertions.assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("maxTagValuesPerMeter"));
    }

    @Test
    void rejects_blank_request_id_header() {
        runner.withPropertyValues("pulse.context.request-id-header=")
                .run(ctx ->
                        Assertions.assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining("requestIdHeader"));
    }

    @Test
    void rejects_zero_async_pool_size() {
        runner.withPropertyValues("pulse.async.core-pool-size=0")
                .run(ctx ->
                        Assertions.assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining("corePoolSize"));
    }

    @Test
    void rejects_headroom_ratio_outside_unit_interval() {
        runner.withPropertyValues("pulse.container-memory.headroom-critical-ratio=2.0")
                .run(ctx -> Assertions.assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("headroomCriticalRatio"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
        SamplingProperties.class,
        CardinalityProperties.class,
        ContextProperties.class,
        AsyncProperties.class,
        ContainerMemoryProperties.class,
    })
    static class EnableProps {}
}
