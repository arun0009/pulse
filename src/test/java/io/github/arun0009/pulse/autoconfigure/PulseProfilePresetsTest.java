package io.github.arun0009.pulse.autoconfigure;

import io.github.arun0009.pulse.autoconfigure.internal.PulseProfilePresetEnvironmentPostProcessor;
import io.github.arun0009.pulse.core.TraceGuardProperties;
import io.github.arun0009.pulse.db.DbProperties;
import io.github.arun0009.pulse.dependencies.DependenciesProperties;
import io.github.arun0009.pulse.enforcement.EnforcementProperties;
import io.github.arun0009.pulse.enforcement.PulseEnforcementMode;
import io.github.arun0009.pulse.guardrails.CardinalityProperties;
import io.github.arun0009.pulse.guardrails.SamplingProperties;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetProperties;
import io.github.arun0009.pulse.startup.BannerProperties;
import io.github.arun0009.pulse.tenant.TenantProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four shipped presets ({@code pulse-dev}, {@code pulse-prod}, {@code pulse-test},
 * {@code pulse-canary}) are the adoption shortcut: a user activates a Spring profile and gets a
 * Pulse profile tuned for that environment.
 *
 * <p>This suite pins two contracts:
 *
 * <ol>
 *   <li>The values inside each preset YAML &mdash; loaded the standard Spring way via
 *       {@code spring.profiles.active=pulse-{env}} &mdash; bind to the documented properties.</li>
 *   <li>The {@link PulseProfilePresetEnvironmentPostProcessor} auto-applies the matching preset
 *       when it sees a common environment name in {@code spring.profiles.active}, and respects
 *       the {@code pulse.profile-presets.auto-apply=false} opt-out.</li>
 * </ol>
 */
class PulseProfilePresetsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableProps.class)
            // ConfigDataApplicationContextInitializer is what scans for application-{profile}.yml
            // on the classpath and binds it. ApplicationContextRunner does not include it by
            // default, so the runner stops at the property-source we hand it.
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
        EnforcementProperties.class,
        SamplingProperties.class,
        TraceGuardProperties.class,
        CardinalityProperties.class,
        TimeoutBudgetProperties.class,
        BannerProperties.class,
        DbProperties.class,
        TenantProperties.class,
        DependenciesProperties.class,
        ProfilePresetsProperties.class,
    })
    static class EnableProps {}

    private ApplicationContextRunner withPulseProfile(String pulseProfile) {
        // Standard Spring profile activation — exactly the path users take when they type
        // spring.profiles.active=pulse-prod. With ConfigDataApplicationContextInitializer wired
        // in, the runner now executes the same config-data pipeline SpringApplication does, so
        // application-pulse-{env}.yml from src/main/resources is picked up.
        return runner.withPropertyValues("spring.profiles.active=" + pulseProfile);
    }

    @Test
    void dev_preset_runs_enforcing_with_full_sampling_and_loose_cardinality_budget() {
        withPulseProfile("pulse-dev").run(ctx -> {
            assertThat(ctx.getBean(EnforcementProperties.class).mode()).isEqualTo(PulseEnforcementMode.Mode.ENFORCING);
            assertThat(ctx.getEnvironment().getProperty("management.tracing.sampling.probability", Double.class))
                    .isEqualTo(1.0);
            assertThat(ctx.getBean(SamplingProperties.class).preferSamplingOnError())
                    .isTrue();
            assertThat(ctx.getBean(TraceGuardProperties.class).failOnMissing()).isFalse();
            assertThat(ctx.getBean(CardinalityProperties.class).maxTagValuesPerMeter())
                    .isEqualTo(5000);
            assertThat(ctx.getBean(BannerProperties.class).enabled()).isTrue();
        });
    }

    @Test
    void prod_preset_runs_enforcing_with_low_sampling_and_strict_guards() {
        withPulseProfile("pulse-prod").run(ctx -> {
            assertThat(ctx.getBean(EnforcementProperties.class).mode()).isEqualTo(PulseEnforcementMode.Mode.ENFORCING);
            assertThat(ctx.getEnvironment().getProperty("management.tracing.sampling.probability", Double.class))
                    .isEqualTo(0.10);
            assertThat(ctx.getBean(SamplingProperties.class).preferSamplingOnError())
                    .isTrue();
            assertThat(ctx.getBean(CardinalityProperties.class).maxTagValuesPerMeter())
                    .isEqualTo(1000);
            assertThat(ctx.getBean(BannerProperties.class).enabled()).isFalse();
            assertThat(ctx.getBean(TenantProperties.class).maxTagCardinality()).isEqualTo(100);
            assertThat(ctx.getBean(DependenciesProperties.class).fanOutWarnThreshold())
                    .isEqualTo(20);
        });
    }

    @Test
    void test_preset_disables_enforcing_features_so_unit_tests_stay_clean() {
        withPulseProfile("pulse-test").run(ctx -> {
            assertThat(ctx.getBean(TraceGuardProperties.class).enabled()).isFalse();
            assertThat(ctx.getBean(CardinalityProperties.class).enabled()).isFalse();
            assertThat(ctx.getBean(TimeoutBudgetProperties.class).enabled()).isFalse();
            assertThat(ctx.getEnvironment().getProperty("management.tracing.sampling.probability", Double.class))
                    .isEqualTo(0.0);
            assertThat(ctx.getBean(SamplingProperties.class).preferSamplingOnError())
                    .isFalse();
            assertThat(ctx.getBean(BannerProperties.class).enabled()).isFalse();
            assertThat(ctx.getBean(DbProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void canary_preset_runs_dry_run_with_full_sampling() {
        withPulseProfile("pulse-canary").run(ctx -> {
            assertThat(ctx.getBean(EnforcementProperties.class).mode())
                    .as("canary must be DRY_RUN — every guardrail observes, none enforces")
                    .isEqualTo(PulseEnforcementMode.Mode.DRY_RUN);
            assertThat(ctx.getEnvironment().getProperty("management.tracing.sampling.probability", Double.class))
                    .isEqualTo(1.0);
            assertThat(ctx.getBean(SamplingProperties.class).preferSamplingOnError())
                    .isTrue();
            assertThat(ctx.getBean(TraceGuardProperties.class).failOnMissing())
                    .as("fail-on-missing stays enabled — DRY_RUN downgrades it at runtime")
                    .isTrue();
        });
    }

    @Test
    void all_presets_pass_jsr_380_validation() {
        for (String preset : new String[] {"pulse-dev", "pulse-prod", "pulse-test", "pulse-canary"}) {
            withPulseProfile(preset).run(ctx -> assertThat(ctx)
                    .as("preset '%s' must bind cleanly", preset)
                    .hasNotFailed());
        }
    }

    // -------- EnvironmentPostProcessor behaviour --------

    @Test
    void epp_appends_pulse_profile_when_environment_name_matches() {
        for (String envName : new String[] {"dev", "prod", "test", "canary"}) {
            MockEnvironment env = new MockEnvironment().withProperty("spring.profiles.active", envName);
            env.setActiveProfiles(envName);

            new PulseProfilePresetEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

            assertThat(env.getActiveProfiles())
                    .as("EPP must append matching pulse-* profile for '%s'", envName)
                    .contains("pulse-" + envName);
        }
    }

    @Test
    void epp_handles_aliases_like_production_local_and_shadow() {
        record Case(String envProfile, String expectedPulseProfile) {}
        List<Case> cases = List.of(
                new Case("production", "pulse-prod"),
                new Case("development", "pulse-dev"),
                new Case("local", "pulse-dev"),
                new Case("integration", "pulse-test"),
                new Case("shadow", "pulse-canary"));

        for (Case c : cases) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(c.envProfile());

            new PulseProfilePresetEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

            assertThat(env.getActiveProfiles())
                    .as("alias '%s' must auto-apply '%s'", c.envProfile(), c.expectedPulseProfile())
                    .contains(c.expectedPulseProfile());
        }
    }

    @Test
    void epp_is_inert_when_pulse_profile_already_explicitly_active() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod", "pulse-prod");

        new PulseProfilePresetEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        long pulseProdCount = Arrays.stream(env.getActiveProfiles())
                .filter("pulse-prod"::equals)
                .count();
        assertThat(pulseProdCount)
                .as("must not duplicate an explicitly-listed pulse profile")
                .isEqualTo(1L);
    }

    @Test
    void epp_is_inert_when_auto_apply_disabled() {
        MockEnvironment env = new MockEnvironment().withProperty("pulse.profile-presets.auto-apply", "false");
        env.setActiveProfiles("prod");

        new PulseProfilePresetEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getActiveProfiles())
                .as("auto-apply=false must keep Pulse hands-off the profile list")
                .containsExactly("prod");
    }

    @Test
    void epp_is_inert_for_unknown_environment_names() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("staging");

        new PulseProfilePresetEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getActiveProfiles())
                .as("Pulse must not invent a preset for an unknown environment name")
                .containsExactly("staging");
    }

    @Test
    void epp_honours_user_override_mapping() {
        MockEnvironment env = new MockEnvironment().withProperty("pulse.profile-presets.presets.prod", "pulse-canary");
        env.setActiveProfiles("prod");

        new PulseProfilePresetEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getActiveProfiles())
                .as("user-supplied mapping must win over the built-in default")
                .contains("pulse-canary");
        assertThat(env.getActiveProfiles())
                .as("the original built-in pulse-prod must NOT be auto-applied when user remapped")
                .doesNotContain("pulse-prod");
    }

    @Test
    void epp_auto_apply_works_end_to_end_through_application_context_runner() {
        // Activate the host environment profile; the EPP must fold pulse-prod in and the standard
        // Spring config-data machinery (ConfigDataApplicationContextInitializer, already wired into
        // the runner) must then load application-pulse-prod.yml.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(EnableProps.class)
                .withInitializer(ctx -> {
                    ctx.getEnvironment().setActiveProfiles("prod");
                    new PulseProfilePresetEnvironmentPostProcessor()
                            .postProcessEnvironment(ctx.getEnvironment(), new SpringApplication());
                })
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(ctx -> assertThat(ctx.getEnvironment()
                                .getProperty("management.tracing.sampling.probability", Double.class))
                        .as(
                                "auto-applied pulse-prod must drag in its 0.10 sampling probability via Boot's standard property")
                        .isEqualTo(0.10));
    }
}
