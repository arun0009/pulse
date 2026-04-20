package io.github.arun0009.pulse.logging;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior of the early-startup post-processor that seeds {@code pulse.app.version} and
 * {@code pulse.build.commit} as JVM system properties so the JSON layout can stamp every log
 * line with them.
 *
 * <p>Each nested class targets one source in the resolution chain so a regression in one
 * source doesn't mask others.
 */
class PulseLoggingEnvironmentPostProcessorTest {

    @BeforeEach
    @AfterEach
    void clearSystemProperties() {
        System.clearProperty(PulseLoggingEnvironmentPostProcessor.VERSION_SYS_PROP);
        System.clearProperty(PulseLoggingEnvironmentPostProcessor.COMMIT_SYS_PROP);
        // Resource-attribute sys-props (host.name, container.id, k8s.*, cloud.*) leak across tests
        // when the default-constructor path runs against the real machine, so we wipe them too.
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith(PulseLoggingEnvironmentPostProcessor.RESOURCE_ATTRIBUTE_SYS_PROP_PREFIX)
                    && !key.equals(PulseLoggingEnvironmentPostProcessor.VERSION_SYS_PROP)
                    && !key.equals(PulseLoggingEnvironmentPostProcessor.COMMIT_SYS_PROP)) {
                System.clearProperty(key);
            }
        }
    }

    @Nested
    class Classpath_source {

        @Test
        void seeds_version_and_commit_from_classpath_metadata() {
            // src/test/resources ships META-INF/build-info.properties + git.properties as
            // fixtures; the default-classloader path picks them up.
            new PulseLoggingEnvironmentPostProcessor()
                    .postProcessEnvironment(new StandardEnvironment(), new SpringApplication());

            assertThat(System.getProperty(PulseLoggingEnvironmentPostProcessor.VERSION_SYS_PROP))
                    .isEqualTo("9.9.9-test");
            assertThat(System.getProperty(PulseLoggingEnvironmentPostProcessor.COMMIT_SYS_PROP))
                    .isEqualTo("deadbeef");
        }
    }

    @Nested
    class Operator_override {

        @Test
        void existing_system_property_is_not_overwritten() {
            // -D on the JVM beats every classpath / env source.
            System.setProperty(PulseLoggingEnvironmentPostProcessor.VERSION_SYS_PROP, "operator-override");
            System.setProperty(PulseLoggingEnvironmentPostProcessor.COMMIT_SYS_PROP, "abc12345");

            new PulseLoggingEnvironmentPostProcessor()
                    .postProcessEnvironment(new StandardEnvironment(), new SpringApplication());

            assertThat(System.getProperty(PulseLoggingEnvironmentPostProcessor.VERSION_SYS_PROP))
                    .isEqualTo("operator-override");
            assertThat(System.getProperty(PulseLoggingEnvironmentPostProcessor.COMMIT_SYS_PROP))
                    .isEqualTo("abc12345");
        }
    }

    @Nested
    class Otel_resource_attributes_source {

        @Test
        void seeds_from_OTEL_RESOURCE_ATTRIBUTES_when_classpath_is_empty() {
            Map<String, String> env = Map.of(
                    "OTEL_RESOURCE_ATTRIBUTES",
                    "service.name=demo,service.version=2.7.4,deployment.commit=cafebabedeadbeef1234567890");

            new PulseLoggingEnvironmentPostProcessor(env::get, emptyClassLoader(), emptyResourceResolver())
                    .postProcessEnvironment(new StandardEnvironment(), new SpringApplication());

            assertThat(System.getProperty(PulseLoggingEnvironmentPostProcessor.VERSION_SYS_PROP))
                    .isEqualTo("2.7.4");
            assertThat(System.getProperty(PulseLoggingEnvironmentPostProcessor.COMMIT_SYS_PROP))
                    .as("commit hash from OTel resource attribute is abbreviated to 8 chars")
                    .isEqualTo("cafebabe");
        }

        @Test
        void parses_url_encoded_otel_values() {
            // Per the OTel spec values are URL-encoded; the parser decodes them.
            String result = PulseLoggingEnvironmentPostProcessor.parseOtelAttribute(
                    "service.version=1.0.0%2Brc1,service.name=svc", "service.version");
            assertThat(result).isEqualTo("1.0.0+rc1");
        }

        @Test
        void returns_null_for_missing_or_empty_attribute() {
            assertThat(PulseLoggingEnvironmentPostProcessor.parseOtelAttribute("service.name=demo", "service.version"))
                    .isNull();
            assertThat(PulseLoggingEnvironmentPostProcessor.parseOtelAttribute("service.version=", "service.version"))
                    .isNull();
            assertThat(PulseLoggingEnvironmentPostProcessor.parseOtelAttribute(null, "service.version"))
                    .isNull();
        }
    }

    @Nested
    class Ci_env_var_source {

        @Test
        void seeds_from_GITHUB_SHA_when_no_higher_priority_source_present() {
            Map<String, String> env = Map.of(
                    "GITHUB_SHA", "fedcba9876543210abcdef0123456789",
                    "BUILD_VERSION", "1.4.2-rc.7");

            new PulseLoggingEnvironmentPostProcessor(env::get, emptyClassLoader(), emptyResourceResolver())
                    .postProcessEnvironment(new StandardEnvironment(), new SpringApplication());

            assertThat(System.getProperty(PulseLoggingEnvironmentPostProcessor.VERSION_SYS_PROP))
                    .isEqualTo("1.4.2-rc.7");
            assertThat(System.getProperty(PulseLoggingEnvironmentPostProcessor.COMMIT_SYS_PROP))
                    .as("CI commit hashes are abbreviated to 8 chars to match git.commit.id.abbrev")
                    .isEqualTo("fedcba98");
        }

        @Test
        void earlier_env_var_in_list_wins_over_later_one() {
            // GIT_COMMIT precedes GITHUB_SHA in the list — first hit wins.
            Map<String, String> env = Map.of(
                    "GIT_COMMIT", "11111111aaaaaaaa",
                    "GITHUB_SHA", "22222222bbbbbbbb");

            new PulseLoggingEnvironmentPostProcessor(env::get, emptyClassLoader(), emptyResourceResolver())
                    .postProcessEnvironment(new StandardEnvironment(), new SpringApplication());

            assertThat(System.getProperty(PulseLoggingEnvironmentPostProcessor.COMMIT_SYS_PROP))
                    .isEqualTo("11111111");
        }

        @Test
        void blank_env_var_falls_through_to_next_source() {
            Map<String, String> env = new HashMap<>();
            env.put("GIT_COMMIT", "");
            env.put("CI_COMMIT_SHA", "deadbeef00112233");

            new PulseLoggingEnvironmentPostProcessor(env::get, emptyClassLoader(), emptyResourceResolver())
                    .postProcessEnvironment(new StandardEnvironment(), new SpringApplication());

            assertThat(System.getProperty(PulseLoggingEnvironmentPostProcessor.COMMIT_SYS_PROP))
                    .isEqualTo("deadbeef");
        }
    }

    @Nested
    class Priority_chain {

        @Test
        void classpath_beats_otel_resource_attributes() {
            // The test classpath has build-info.properties=9.9.9-test; OTel attr should be ignored.
            Map<String, String> env = Map.of("OTEL_RESOURCE_ATTRIBUTES", "service.version=lower-priority");

            new PulseLoggingEnvironmentPostProcessor(env::get, defaultTestClassLoader(), emptyResourceResolver())
                    .postProcessEnvironment(new StandardEnvironment(), new SpringApplication());

            assertThat(System.getProperty(PulseLoggingEnvironmentPostProcessor.VERSION_SYS_PROP))
                    .isEqualTo("9.9.9-test");
        }

        @Test
        void otel_resource_attributes_beats_plain_env_vars() {
            Map<String, String> env = Map.of(
                    "OTEL_RESOURCE_ATTRIBUTES", "service.version=otel-wins",
                    "BUILD_VERSION", "env-loses");

            new PulseLoggingEnvironmentPostProcessor(env::get, emptyClassLoader(), emptyResourceResolver())
                    .postProcessEnvironment(new StandardEnvironment(), new SpringApplication());

            assertThat(System.getProperty(PulseLoggingEnvironmentPostProcessor.VERSION_SYS_PROP))
                    .isEqualTo("otel-wins");
        }
    }

    @Nested
    class No_sources_present {

        @Test
        void leaves_system_properties_unset_so_layout_can_substitute_unknown() {
            // Without a main-class manifest fallback (the test runner's main class would
            // otherwise leak surefire's version), every source is empty.
            new PulseLoggingEnvironmentPostProcessor(name -> null, emptyClassLoader(), emptyResourceResolver())
                    .postProcessEnvironment(new StandardEnvironment(), springApplicationWithoutMainClass());

            assertThat(System.getProperty(PulseLoggingEnvironmentPostProcessor.VERSION_SYS_PROP))
                    .as("post-processor must not seed a value when no source produced one")
                    .isNull();
            assertThat(System.getProperty(PulseLoggingEnvironmentPostProcessor.COMMIT_SYS_PROP))
                    .isNull();
        }
    }

    @Nested
    class Manifest_fallback {

        @Test
        void reads_implementation_version_from_main_class_package() {
            // SpringApplication() called from a test deduces surefire's ForkedBooter as
            // the main class — its package's Implementation-Version is the surefire version.
            // In production this resolves to the consumer's @SpringBootApplication class,
            // whose JAR manifest is populated by spring-boot-maven-plugin with the pom
            // <version>. We assert only that *something* non-blank is returned, since the
            // exact value depends on which surefire JAR is on the classpath.
            String version = new PulseLoggingEnvironmentPostProcessor(
                            name -> null, emptyClassLoader(), emptyResourceResolver())
                    .resolveVersion(new SpringApplication());

            assertThat(version)
                    .as("manifest fallback should resolve Implementation-Version from the main class JAR")
                    .isNotBlank();
        }
    }

    /**
     * Returns a {@link SpringApplication} whose {@code getMainApplicationClass()} returns {@code null},
     * suppressing the manifest-version fallback. Used by tests that need to assert a fully empty
     * resolution chain.
     */
    private static SpringApplication springApplicationWithoutMainClass() {
        return new SpringApplication() {
            @Override
            public Class<?> getMainApplicationClass() {
                return null;
            }
        };
    }

    /**
     * A classloader that returns nothing for {@code META-INF/build-info.properties} or
     * {@code git.properties} but otherwise delegates. Lets tests exercise the env-var paths
     * without the test fixtures shadowing them.
     */
    private static ClassLoader emptyClassLoader() {
        ClassLoader parent = ClassLoader.getSystemClassLoader();
        return new ClassLoader(parent) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (PulseLoggingEnvironmentPostProcessor.BUILD_INFO_RESOURCE.equals(name)
                        || PulseLoggingEnvironmentPostProcessor.GIT_PROPERTIES_RESOURCE.equals(name)) {
                    return null;
                }
                return super.getResourceAsStream(name);
            }
        };
    }

    /** Real test classloader so the fixtures in {@code src/test/resources} are visible. */
    private static ClassLoader defaultTestClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return (cl != null) ? cl : PulseLoggingEnvironmentPostProcessorTest.class.getClassLoader();
    }

    /**
     * A resolver wired to return nothing for every probe (env, sysprop, file, hostname). Used
     * by tests that exercise the version/commit chain in isolation, so the resource-attribute
     * detection can't accidentally read the real test machine's environment and assert against
     * leaked values.
     */
    private static ResourceAttributeResolver emptyResourceResolver() {
        Function<String, @Nullable String> nothingByName = name -> null;
        Function<Path, @Nullable String> nothingByPath = path -> null;
        return new StubResourceAttributeResolver(nothingByName, nothingByPath, () -> null);
    }

    /** Subclass exposing the {@code protected} test-seam constructor. */
    private static final class StubResourceAttributeResolver extends ResourceAttributeResolver {
        StubResourceAttributeResolver(
                Function<String, @Nullable String> envLookup,
                Function<Path, @Nullable String> fileReader,
                HostNameProvider hostNameProvider) {
            super(envLookup, fileReader, hostNameProvider);
        }
    }
}
