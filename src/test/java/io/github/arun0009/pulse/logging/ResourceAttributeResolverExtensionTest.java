package io.github.arun0009.pulse.logging;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Pulse 2.0 extension contract for {@link ResourceAttributeResolver}: subclasses
 * can replace single accessors and add custom attributes via {@link
 * ResourceAttributeResolver#contributeAdditional()}, with built-in OTel semconv keys taking
 * precedence on collision.
 */
class ResourceAttributeResolverExtensionTest {

    @Nested
    class Contribute_additional {

        @Test
        void custom_attributes_appear_in_resolve_all() {
            ResourceAttributeResolver resolver = new CustomAttributesResolver(Map.of(
                    "deployment.id", "deploy-42",
                    "instance.type", "m6i.large"));

            Map<String, String> attributes = resolver.resolveAll();

            assertThat(attributes)
                    .containsEntry("deployment.id", "deploy-42")
                    .containsEntry("instance.type", "m6i.large");
        }

        @Test
        void built_in_semconv_keys_win_on_collision() {
            // Subclass tries to claim host.name; built-in detection wins so the OTel semconv
            // shape stays canonical and dashboards don't see two competing values.
            ResourceAttributeResolver resolver = new CustomAttributesResolver(
                    Map.of("host.name", "subclass-says-this", "deployment.id", "deploy-42"),
                    () -> "built-in-says-this");

            Map<String, String> attributes = resolver.resolveAll();

            assertThat(attributes)
                    .as("built-in semconv key wins so the canonical OTel attribute is preserved")
                    .containsEntry("host.name", "built-in-says-this")
                    .containsEntry("deployment.id", "deploy-42");
        }

        @Test
        void blank_or_null_extra_values_are_omitted() {
            Map<String, String> sparse = new LinkedHashMap<>();
            sparse.put("deployment.id", "deploy-42");
            sparse.put("blank.value", "   ");

            ResourceAttributeResolver resolver = new CustomAttributesResolver(sparse);

            assertThat(resolver.resolveAll())
                    .containsEntry("deployment.id", "deploy-42")
                    .doesNotContainKey("blank.value");
        }
    }

    @Nested
    class Override_single_accessor {

        @Test
        void subclass_can_replace_host_name_resolution_only() {
            ResourceAttributeResolver resolver = new ResourceAttributeResolver(name -> null, path -> null, () -> null) {
                @Override
                protected @Nullable String hostName() {
                    return "from-ec2-metadata";
                }
            };

            assertThat(resolver.resolveAll())
                    .as("only hostName() is overridden; the rest of the chain still runs and yields nothing")
                    .containsEntry("host.name", "from-ec2-metadata")
                    .hasSize(1);
        }
    }

    /**
     * Concrete subclass exercising the public extension constructor. Production users would
     * typically register one like this via {@code spring.factories}.
     */
    private static final class CustomAttributesResolver extends ResourceAttributeResolver {
        private final Map<String, String> extras;

        CustomAttributesResolver(Map<String, String> extras) {
            super(emptyHostNameProvider());
            this.extras = extras;
        }

        CustomAttributesResolver(Map<String, String> extras, HostNameProvider hostName) {
            super(envOf(), fileReaderOf(), hostName);
            this.extras = extras;
        }

        @Override
        protected Map<String, String> contributeAdditional() {
            return extras;
        }

        private static HostNameProvider emptyHostNameProvider() {
            return () -> null;
        }

        private static Function<String, @Nullable String> envOf() {
            return name -> null;
        }

        private static Function<Path, @Nullable String> fileReaderOf() {
            return path -> null;
        }
    }
}
