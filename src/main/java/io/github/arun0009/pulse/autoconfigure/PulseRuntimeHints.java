package io.github.arun0009.pulse.autoconfigure;

import io.github.arun0009.pulse.logging.PiiMaskingConverter;
import io.github.arun0009.pulse.logging.PulseLogbackEncoder;
import io.github.arun0009.pulse.propagation.PulseKafkaProducerInterceptor;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.util.ClassUtils;

/**
 * GraalVM native-image hints for Pulse.
 *
 * <p>Spring Boot's own AOT processors handle the easy stuff: {@code @ConfigurationProperties}
 * records, {@code @Bean} methods, {@code @Endpoint} actuator surfaces, and Micrometer's
 * {@code MeterFilter} chain. This class registers everything Spring AOT cannot infer:
 *
 * <ul>
 *   <li>{@link PulseKafkaProducerInterceptor} — Kafka instantiates this reflectively from a
 *       string class name in {@code ProducerConfig.INTERCEPTOR_CLASSES_CONFIG}; Spring never
 *       sees it as a bean and therefore never adds reflection metadata for it. Registered only
 *       when the Kafka client is on the classpath ({@code spring-kafka} is optional for Pulse).
 *   <li>{@link PiiMaskingConverter} — discovered by Log4j2 via its plugin scanner. Registered
 *       only when log4j-core is present; the converter extends Log4j2 types and must not load
 *       on a Logback-only native image.
 *   <li>JSON layout templates and {@code log4j2-spring.xml} / {@code logback-spring.xml},
 *       registered as resources because GraalVM does not bundle classpath resources unless
 *       hinted.
 * </ul>
 *
 * <p>Wired via {@code @ImportRuntimeHints(PulseRuntimeHints.class)} on
 * {@code PulseAutoConfiguration} so it runs once during Spring's AOT processing phase whenever
 * Pulse is on the classpath.
 *
 * <p>This class is purely additive: it has no effect on JAR-on-OpenJDK deployments. It is only
 * invoked during {@code mvn -Pnative} / Spring's AOT phase.
 */
public class PulseRuntimeHints implements RuntimeHintsRegistrar {

    /** Marker type: Kafka is an optional dependency; do not touch {@link PulseKafkaProducerInterceptor} without it. */
    private static final String KAFKA_PRODUCER_INTERCEPTOR = "org.apache.kafka.clients.producer.ProducerInterceptor";

    /** Marker type: Log4j2 is optional; do not load {@link PiiMaskingConverter} on a Logback-only native image. */
    private static final String LOG4J2_CONVERTER = "org.apache.logging.log4j.core.pattern.LogEventPatternConverter";

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        ClassLoader cl = classLoader != null ? classLoader : PulseRuntimeHints.class.getClassLoader();
        // Kafka producer interceptor — instantiated by Apache Kafka via Class.forName + no-arg
        // constructor; not visible to Spring's reflection registrar. Skip when Kafka is not on
        // the classpath (e.g. Pulse showcase edge app) — registering the type would load
        // PulseKafkaProducerInterceptor and fail AOT with NoClassDefFoundError.
        if (ClassUtils.isPresent(KAFKA_PRODUCER_INTERCEPTOR, cl)) {
            hints.reflection()
                    .registerType(
                            PulseKafkaProducerInterceptor.class,
                            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                            MemberCategory.INVOKE_PUBLIC_METHODS);
        }

        // Log4j2 plugin discovered by classpath scan. The converter extends log4j-core; loading
        // the class on a Logback-only app blows AOT with ClassNotFoundException:
        // LogEventPatternConverter.
        if (ClassUtils.isPresent(LOG4J2_CONVERTER, cl)) {
            hints.reflection()
                    .registerType(
                            PiiMaskingConverter.class,
                            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                            MemberCategory.INVOKE_PUBLIC_METHODS,
                            MemberCategory.INVOKE_DECLARED_METHODS);
            hints.resources()
                    .registerPattern("META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat");
        }

        // Logback encoder — instantiated by Logback's JoranConfigurator from a class name in
        // logback-spring.xml; native image needs the public constructor and methods reachable.
        hints.reflection()
                .registerType(
                        PulseLogbackEncoder.class,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS);

        // Resources Pulse ships and reads at runtime.
        hints.resources().registerPattern("pulse-json-layout.json");
        hints.resources().registerPattern("log4j2-spring.xml");
        hints.resources().registerPattern("logback-spring.xml");
    }
}
