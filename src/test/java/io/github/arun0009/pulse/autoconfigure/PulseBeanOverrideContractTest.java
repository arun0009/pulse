package io.github.arun0009.pulse.autoconfigure;

import io.github.arun0009.pulse.dependencies.RequestFanoutFilter;
import io.github.arun0009.pulse.dependencies.internal.PulseDependenciesConfiguration;
import io.github.arun0009.pulse.logging.HostNameProvider;
import io.github.arun0009.pulse.logging.ResourceAttributeResolver;
import io.github.arun0009.pulse.metrics.internal.CommonTagsConfiguration;
import io.github.arun0009.pulse.propagation.internal.RestClientPropagationConfiguration;
import io.github.arun0009.pulse.propagation.internal.RestTemplatePropagationConfiguration;
import io.github.arun0009.pulse.propagation.internal.WebClientPropagationConfiguration;
import io.github.arun0009.pulse.resilience.RetryDepthFilter;
import io.github.arun0009.pulse.resilience.internal.PulseRetryAmplificationConfiguration;
import io.github.arun0009.pulse.tenant.TenantContextFilter;
import io.github.arun0009.pulse.tenant.internal.PulseTenantConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Phase 3c override contract: every Pulse {@code @Bean} that an application has a
 * legitimate reason to replace is gated on
 * {@link org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
 * @ConditionalOnMissingBean} so a user-supplied bean of the same name (or type) wins.
 *
 * <p>Each test publishes a stub bean and asserts that Pulse's auto-config respects the user
 * override instead of registering its own. {@link PulseAutoConfiguration} is loaded alongside
 * the focused auto-config under test so the {@code @ConfigurationProperties} beans Pulse
 * depends on are present.
 */
class PulseBeanOverrideContractTest {

    private static final AutoConfigurations PULSE_BASE = AutoConfigurations.of(PulseAutoConfiguration.class);

    @Nested
    class Common_tags {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withConfiguration(PULSE_BASE)
                .withConfiguration(AutoConfigurations.of(CommonTagsConfiguration.class));

        @Test
        void user_supplied_pulse_common_tags_replaces_pulse_default() {
            runner.withUserConfiguration(UserCommonTagsConfig.class).run(context -> {
                MeterRegistryCustomizer<?> customizer =
                        context.getBean("pulseCommonTags", MeterRegistryCustomizer.class);
                assertThat(customizer)
                        .as("user-supplied pulseCommonTags must take over the bean name")
                        .isSameAs(context.getBean(UserCommonTagsConfig.class).marker);
            });
        }
    }

    @Nested
    class Request_fanout_filter {

        private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withConfiguration(PULSE_BASE)
                .withConfiguration(AutoConfigurations.of(PulseDependenciesConfiguration.class));

        @Test
        void user_supplied_request_fanout_filter_replaces_pulse_default() {
            runner.withUserConfiguration(UserFanoutFilterConfig.class).run(context -> {
                @SuppressWarnings("unchecked")
                FilterRegistrationBean<RequestFanoutFilter> registration =
                        context.getBean("pulseRequestFanoutFilterRegistration", FilterRegistrationBean.class);
                assertThat(registration)
                        .as("user-supplied pulseRequestFanoutFilterRegistration must take over the bean name")
                        .isSameAs(context.getBean(UserFanoutFilterConfig.class).marker);
            });
        }
    }

    @Nested
    class Tenant_context_filter {

        private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withConfiguration(PULSE_BASE)
                .withConfiguration(AutoConfigurations.of(PulseTenantConfiguration.class));

        @Test
        void user_supplied_tenant_context_filter_replaces_pulse_default() {
            runner.withUserConfiguration(UserTenantFilterConfig.class).run(context -> {
                @SuppressWarnings("unchecked")
                FilterRegistrationBean<TenantContextFilter> registration =
                        context.getBean("pulseTenantContextFilter", FilterRegistrationBean.class);
                assertThat(registration)
                        .as("user-supplied pulseTenantContextFilter must take over the bean name")
                        .isSameAs(context.getBean(UserTenantFilterConfig.class).marker);
            });
        }
    }

    @Nested
    class Retry_depth_filter {

        private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withConfiguration(PULSE_BASE)
                .withConfiguration(AutoConfigurations.of(PulseRetryAmplificationConfiguration.class));

        @Test
        void user_supplied_retry_depth_filter_replaces_pulse_default() {
            runner.withUserConfiguration(UserRetryFilterConfig.class).run(context -> {
                @SuppressWarnings("unchecked")
                FilterRegistrationBean<RetryDepthFilter> registration =
                        context.getBean("pulseRetryDepthFilter", FilterRegistrationBean.class);
                assertThat(registration)
                        .as("user-supplied pulseRetryDepthFilter must take over the bean name")
                        .isSameAs(context.getBean(UserRetryFilterConfig.class).marker);
            });
        }
    }

    @Nested
    class Logging_spis {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withConfiguration(PULSE_BASE);

        @Test
        void user_supplied_host_name_provider_replaces_pulse_default() {
            runner.withUserConfiguration(UserHostNameProviderConfig.class).run(context -> {
                HostNameProvider provider = context.getBean(HostNameProvider.class);
                assertThat(provider).isSameAs(context.getBean(UserHostNameProviderConfig.class).marker);
            });
        }

        @Test
        void user_supplied_resource_attribute_resolver_replaces_pulse_default() {
            runner.withUserConfiguration(UserResolverConfig.class).run(context -> {
                ResourceAttributeResolver resolver = context.getBean(ResourceAttributeResolver.class);
                assertThat(resolver).isSameAs(context.getBean(UserResolverConfig.class).marker);
            });
        }
    }

    @Nested
    class Propagation_customizers {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withConfiguration(PULSE_BASE)
                .withConfiguration(AutoConfigurations.of(
                        RestTemplatePropagationConfiguration.class,
                        RestClientPropagationConfiguration.class,
                        WebClientPropagationConfiguration.class));

        @Test
        void user_supplied_rest_template_customizer_replaces_pulse_default() {
            runner.withUserConfiguration(UserRestTemplateCustomizerConfig.class).run(context -> {
                RestTemplateCustomizer customizer =
                        context.getBean("pulseRestTemplateCustomizer", RestTemplateCustomizer.class);
                assertThat(customizer).isSameAs(context.getBean(UserRestTemplateCustomizerConfig.class).marker);
            });
        }

        @Test
        void user_supplied_rest_client_customizer_replaces_pulse_default() {
            runner.withUserConfiguration(UserRestClientCustomizerConfig.class).run(context -> {
                RestClientCustomizer customizer =
                        context.getBean("pulseRestClientCustomizer", RestClientCustomizer.class);
                assertThat(customizer).isSameAs(context.getBean(UserRestClientCustomizerConfig.class).marker);
            });
        }

        @Test
        void user_supplied_web_client_customizer_replaces_pulse_default() {
            runner.withUserConfiguration(UserWebClientCustomizerConfig.class).run(context -> {
                WebClientCustomizer customizer = context.getBean("pulseWebClientCustomizer", WebClientCustomizer.class);
                assertThat(customizer).isSameAs(context.getBean(UserWebClientCustomizerConfig.class).marker);
            });
        }
    }

    @Nested
    class Dependency_customizers {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withConfiguration(PULSE_BASE)
                .withConfiguration(AutoConfigurations.of(PulseDependenciesConfiguration.class));

        @Test
        void user_supplied_dependency_rest_template_customizer_replaces_pulse_default() {
            runner.withUserConfiguration(UserDependencyRestTemplateCustomizerConfig.class)
                    .run(context -> {
                        RestTemplateCustomizer customizer =
                                context.getBean("pulseDependencyRestTemplateCustomizer", RestTemplateCustomizer.class);
                        assertThat(customizer)
                                .isSameAs(context.getBean(UserDependencyRestTemplateCustomizerConfig.class).marker);
                    });
        }

        @Test
        void user_supplied_dependency_rest_client_customizer_replaces_pulse_default() {
            runner.withUserConfiguration(UserDependencyRestClientCustomizerConfig.class)
                    .run(context -> {
                        RestClientCustomizer customizer =
                                context.getBean("pulseDependencyRestClientCustomizer", RestClientCustomizer.class);
                        assertThat(customizer)
                                .isSameAs(context.getBean(UserDependencyRestClientCustomizerConfig.class).marker);
                    });
        }

        @Test
        void user_supplied_dependency_web_client_customizer_replaces_pulse_default() {
            runner.withUserConfiguration(UserDependencyWebClientCustomizerConfig.class)
                    .run(context -> {
                        WebClientCustomizer customizer =
                                context.getBean("pulseDependencyWebClientCustomizer", WebClientCustomizer.class);
                        assertThat(customizer)
                                .isSameAs(context.getBean(UserDependencyWebClientCustomizerConfig.class).marker);
                    });
        }
    }

    /* ---------- user-supplied configurations ---------- */

    @Configuration
    static class UserCommonTagsConfig {
        final MeterRegistryCustomizer<MeterRegistry> marker = registry -> {};

        @Bean
        public MeterRegistryCustomizer<MeterRegistry> pulseCommonTags() {
            return marker;
        }
    }

    @Configuration
    static class UserFanoutFilterConfig {
        final FilterRegistrationBean<RequestFanoutFilter> marker = new FilterRegistrationBean<>();

        @Bean
        public FilterRegistrationBean<RequestFanoutFilter> pulseRequestFanoutFilterRegistration() {
            return marker;
        }
    }

    @Configuration
    static class UserTenantFilterConfig {
        final FilterRegistrationBean<TenantContextFilter> marker = new FilterRegistrationBean<>();

        @Bean
        public FilterRegistrationBean<TenantContextFilter> pulseTenantContextFilter() {
            return marker;
        }
    }

    @Configuration
    static class UserRetryFilterConfig {
        final FilterRegistrationBean<RetryDepthFilter> marker = new FilterRegistrationBean<>();

        @Bean
        public FilterRegistrationBean<RetryDepthFilter> pulseRetryDepthFilter() {
            return marker;
        }
    }

    @Configuration
    static class UserHostNameProviderConfig {
        final HostNameProvider marker = () -> "user-host";

        @Bean
        public HostNameProvider hostNameProvider() {
            return marker;
        }
    }

    @Configuration
    static class UserResolverConfig {
        final HostNameProvider host = () -> "user-host";
        final ResourceAttributeResolver marker = new ResourceAttributeResolver(host);

        @Bean
        public ResourceAttributeResolver resourceAttributeResolver() {
            return marker;
        }

        @Bean
        public HostNameProvider hostNameProvider() {
            return host;
        }
    }

    @Configuration
    static class UserRestTemplateCustomizerConfig {
        final RestTemplateCustomizer marker = restTemplate -> {};

        @Bean
        public RestTemplateCustomizer pulseRestTemplateCustomizer() {
            return marker;
        }
    }

    @Configuration
    static class UserRestClientCustomizerConfig {
        final RestClientCustomizer marker = builder -> {};

        @Bean
        public RestClientCustomizer pulseRestClientCustomizer() {
            return marker;
        }
    }

    @Configuration
    static class UserWebClientCustomizerConfig {
        final WebClientCustomizer marker = builder -> {};

        @Bean
        public WebClientCustomizer pulseWebClientCustomizer() {
            return marker;
        }
    }

    @Configuration
    static class UserDependencyRestTemplateCustomizerConfig {
        final RestTemplateCustomizer marker = restTemplate -> {};

        @Bean
        public RestTemplateCustomizer pulseDependencyRestTemplateCustomizer() {
            return marker;
        }
    }

    @Configuration
    static class UserDependencyRestClientCustomizerConfig {
        final RestClientCustomizer marker = builder -> {};

        @Bean
        public RestClientCustomizer pulseDependencyRestClientCustomizer() {
            return marker;
        }
    }

    @Configuration
    static class UserDependencyWebClientCustomizerConfig {
        final WebClientCustomizer marker = builder -> {};

        @Bean
        public WebClientCustomizer pulseDependencyWebClientCustomizer() {
            return marker;
        }
    }
}
