package io.github.arun0009.pulse.guardrails.internal;

import io.github.arun0009.pulse.guardrails.PreferErrorSampler;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pulse 2.0 owns no {@code Sampler} bean of its own. Instead, a {@link
 * org.springframework.beans.factory.config.BeanPostProcessor BeanPostProcessor} wraps whatever
 * {@code Sampler} is on the context with {@link PreferErrorSampler} so error spans are recorded
 * even when the head sampler would have dropped them. This suite pins both halves of that
 * contract.
 */
class SamplingConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withConfiguration(AutoConfigurations.of(SamplingConfiguration.class));

    @Test
    void wraps_user_supplied_sampler_with_prefer_error_sampler_by_default() {
        runner.withBean("userSampler", Sampler.class, () -> Sampler.alwaysOn()).run(ctx -> {
            Sampler actual = ctx.getBean("userSampler", Sampler.class);
            assertThat(actual)
                    .as("default behaviour wraps the discovered Sampler bean")
                    .isInstanceOf(PreferErrorSampler.class);
        });
    }

    @Test
    void leaves_sampler_untouched_when_explicitly_disabled() {
        runner.withPropertyValues("pulse.sampling.prefer-sampling-on-error=false")
                .withBean("userSampler", Sampler.class, () -> Sampler.alwaysOn())
                .run(ctx -> {
                    Sampler actual = ctx.getBean("userSampler", Sampler.class);
                    assertThat(actual)
                            .as("opting out of the error-bias wrapper must leave the user sampler alone")
                            .isNotInstanceOf(PreferErrorSampler.class);
                });
    }

    @Test
    void does_not_double_wrap_already_wrapped_sampler() {
        runner.withBean("userSampler", Sampler.class, () -> new PreferErrorSampler(Sampler.alwaysOn()))
                .run(ctx -> {
                    Sampler actual = ctx.getBean("userSampler", Sampler.class);
                    assertThat(actual).isInstanceOf(PreferErrorSampler.class);
                    assertThat(actual.getDescription())
                            .as("must not produce PreferErrorSampler{PreferErrorSampler{...}}")
                            .doesNotContain("PreferErrorSampler{PreferErrorSampler");
                });
    }
}
