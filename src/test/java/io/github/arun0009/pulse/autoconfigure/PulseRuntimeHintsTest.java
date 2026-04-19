package io.github.arun0009.pulse.autoconfigure;

import io.github.arun0009.pulse.logging.PiiMaskingConverter;
import io.github.arun0009.pulse.propagation.PulseKafkaProducerInterceptor;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;

import static org.springframework.aot.hint.predicate.RuntimeHintsPredicates.reflection;
import static org.springframework.aot.hint.predicate.RuntimeHintsPredicates.resource;

/**
 * Verifies the contract of {@link PulseRuntimeHints} without spinning up a native-image build.
 *
 * <p>If a future change adds a new reflective dependency (a Kafka interceptor, a Log4j2 plugin,
 * a resource read at runtime) and forgets to update the hints, this test fails locally and in CI
 * — protecting the native-image story without paying the 5-minute native-image-build tax on every
 * commit.
 *
 * <p>The actual native build is exercised by the consuming app's own {@code mvn -Pnative test}
 * which we recommend in the README. There is no value in re-running it here.
 */
class PulseRuntimeHintsTest {

    @Test
    void registers_kafka_producer_interceptor_for_reflection_with_default_constructor() {
        // Apache Kafka instantiates this class via Class.forName(...).getDeclaredConstructor().newInstance()
        // — without this hint, native image throws ClassNotFoundException on first send().
        RuntimeHints hints = new RuntimeHints();
        new PulseRuntimeHints().registerHints(hints, getClass().getClassLoader());

        Assertions.assertThat(reflection().onType(PulseKafkaProducerInterceptor.class))
                .accepts(hints);
    }

    @Test
    void registers_pii_masking_converter_for_reflection() {
        // Log4j2's plugin scanner uses reflection to discover the @Plugin-annotated converter.
        RuntimeHints hints = new RuntimeHints();
        new PulseRuntimeHints().registerHints(hints, getClass().getClassLoader());

        Assertions.assertThat(reflection().onType(PiiMaskingConverter.class)).accepts(hints);
    }

    @Test
    void registers_classpath_resources_pulse_reads_at_runtime() {
        RuntimeHints hints = new RuntimeHints();
        new PulseRuntimeHints().registerHints(hints, getClass().getClassLoader());

        Assertions.assertThat(resource().forResource("pulse-json-layout.json")).accepts(hints);
        Assertions.assertThat(resource().forResource("log4j2-spring.xml")).accepts(hints);
    }

    @Test
    void registers_log4j2_plugin_descriptor_so_native_image_can_locate_pulse_plugins() {
        RuntimeHints hints = new RuntimeHints();
        new PulseRuntimeHints().registerHints(hints, getClass().getClassLoader());

        Assertions.assertThat(resource()
                        .forResource("META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat"))
                .accepts(hints);
    }
}
