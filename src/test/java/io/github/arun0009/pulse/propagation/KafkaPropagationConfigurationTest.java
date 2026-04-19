package io.github.arun0009.pulse.propagation;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.RecordInterceptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioral tests for the composing pieces of {@link KafkaPropagationConfiguration}: the
 * {@code CompositeRecordInterceptor} ordering contract and the {@code ProducerFactoryCustomizer}
 * that splices Pulse's interceptor into Kafka producer configs without trampling user values.
 */
class KafkaPropagationConfigurationTest {

    @Test
    void compositeInterceptOrderRunsPulseFirstThenUserInterceptorsInDeclaredOrder() {
        // Pulse must hydrate MDC before user interceptors observe the record so they can read
        // requestId / userId / etc. from MDC if they want to.
        List<String> sequence = new ArrayList<>();
        PulseKafkaRecordInterceptor pulse = trackingPulseInterceptor(sequence);
        RecordInterceptor<Object, Object> userA = trackingUser(sequence, "userA");
        RecordInterceptor<Object, Object> userB = trackingUser(sequence, "userB");

        var composite = new KafkaPropagationConfiguration.CompositeRecordInterceptor(pulse, List.of(userA, userB));

        composite.intercept(record(), null);

        assertThat(sequence).containsExactly("pulse:intercept", "userA:intercept", "userB:intercept");
    }

    @Test
    void compositeAfterRecordRunsUserInterceptorsFirstThenPulseLast() {
        // Symmetric to intercept: Pulse cleans up MDC after user interceptors finish, so they
        // observe a fully populated context throughout their lifecycle.
        List<String> sequence = new ArrayList<>();
        PulseKafkaRecordInterceptor pulse = trackingPulseInterceptor(sequence);
        RecordInterceptor<Object, Object> userA = trackingUser(sequence, "userA");
        RecordInterceptor<Object, Object> userB = trackingUser(sequence, "userB");

        var composite = new KafkaPropagationConfiguration.CompositeRecordInterceptor(pulse, List.of(userA, userB));

        composite.afterRecord(record(), null);

        assertThat(sequence).containsExactly("userA:after", "userB:after", "pulse:after");
    }

    @Test
    void userInterceptorThrowingDuringAfterRecordDoesNotPreventPulseCleanup() {
        // Critical: a buggy user interceptor must not leak MDC. Pulse swallows their exception
        // so its own afterRecord still runs.
        List<String> sequence = new ArrayList<>();
        PulseKafkaRecordInterceptor pulse = trackingPulseInterceptor(sequence);
        RecordInterceptor<Object, Object> bad = new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<Object, Object> intercept(
                    ConsumerRecord<Object, Object> rec, Consumer<Object, Object> consumer) {
                return rec;
            }

            @Override
            public void afterRecord(ConsumerRecord<Object, Object> rec, Consumer<Object, Object> consumer) {
                sequence.add("bad:after");
                throw new RuntimeException("buggy user interceptor");
            }
        };

        var composite = new KafkaPropagationConfiguration.CompositeRecordInterceptor(pulse, List.of(bad));

        composite.afterRecord(record(), null);

        assertThat(sequence).containsExactly("bad:after", "pulse:after");
    }

    @Test
    void compositeInterceptShortCircuitsWhenUpstreamReturnsNull() {
        // If pulse (or any earlier interceptor) drops the record, downstream interceptors must
        // not be invoked — matches Spring Kafka's null-means-drop convention.
        AtomicInteger laterCalls = new AtomicInteger();
        PulseKafkaRecordInterceptor droppingPulse = new PulseKafkaRecordInterceptor(defaultProperties()) {
            @Override
            public ConsumerRecord<Object, Object> intercept(
                    ConsumerRecord<Object, Object> rec, Consumer<Object, Object> consumer) {
                return null;
            }
        };
        RecordInterceptor<Object, Object> later = new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<Object, Object> intercept(
                    ConsumerRecord<Object, Object> rec, Consumer<Object, Object> consumer) {
                laterCalls.incrementAndGet();
                return rec;
            }
        };

        var composite = new KafkaPropagationConfiguration.CompositeRecordInterceptor(droppingPulse, List.of(later));

        ConsumerRecord<Object, Object> result = composite.intercept(record(), null);

        assertThat(result).isNull();
        assertThat(laterCalls.get()).isZero();
    }

    @Test
    void producerFactoryCustomizerInsertsPulseInterceptorIntoEmptyConfig() {
        // No prior interceptor.classes => Pulse becomes the sole entry.
        DefaultKafkaProducerFactory<Object, Object> factory = new DefaultKafkaProducerFactory<>(new HashMap<>());
        var customizer = new KafkaPropagationConfiguration.ProducerFactoryCustomizer();

        customizer.postProcessAfterInitialization(factory, "pf");

        Map<String, Object> configs = factory.getConfigurationProperties();
        assertThat(configs.get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG))
                .isEqualTo(PulseKafkaProducerInterceptor.class.getName());
    }

    @Test
    void producerFactoryCustomizerAppendsPulseToExistingInterceptorChain() {
        // Pre-existing user interceptor must be preserved — Pulse appends, never replaces.
        Map<String, Object> initial = new HashMap<>();
        initial.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, "com.acme.MyInterceptor");
        DefaultKafkaProducerFactory<Object, Object> factory = new DefaultKafkaProducerFactory<>(initial);
        var customizer = new KafkaPropagationConfiguration.ProducerFactoryCustomizer();

        customizer.postProcessAfterInitialization(factory, "pf");

        String chain = (String) factory.getConfigurationProperties().get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG);
        assertThat(chain).isEqualTo("com.acme.MyInterceptor," + PulseKafkaProducerInterceptor.class.getName());
    }

    @Test
    void producerFactoryCustomizerIsIdempotentAcrossReprocessing() {
        // BeanPostProcessor may run more than once in some test contexts — must not double-add.
        DefaultKafkaProducerFactory<Object, Object> factory = new DefaultKafkaProducerFactory<>(new HashMap<>());
        var customizer = new KafkaPropagationConfiguration.ProducerFactoryCustomizer();

        customizer.postProcessAfterInitialization(factory, "pf");
        customizer.postProcessAfterInitialization(factory, "pf");
        customizer.postProcessAfterInitialization(factory, "pf");

        String chain = (String) factory.getConfigurationProperties().get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG);
        assertThat(chain).isEqualTo(PulseKafkaProducerInterceptor.class.getName());
    }

    @Test
    void producerFactoryCustomizerLeavesNonProducerFactoryBeansUntouched() {
        var customizer = new KafkaPropagationConfiguration.ProducerFactoryCustomizer();

        Object result = customizer.postProcessAfterInitialization("just a string", "name");

        assertThat(result).isEqualTo("just a string");
    }

    private static ConsumerRecord<Object, Object> record() {
        return new ConsumerRecord<>("topic", 0, 0L, "k", "v");
    }

    private static PulseKafkaRecordInterceptor trackingPulseInterceptor(List<String> sequence) {
        return new PulseKafkaRecordInterceptor(defaultProperties()) {
            @Override
            public ConsumerRecord<Object, Object> intercept(
                    ConsumerRecord<Object, Object> rec, Consumer<Object, Object> consumer) {
                sequence.add("pulse:intercept");
                return rec;
            }

            @Override
            public void afterRecord(ConsumerRecord<Object, Object> rec, Consumer<Object, Object> consumer) {
                sequence.add("pulse:after");
            }
        };
    }

    private static RecordInterceptor<Object, Object> trackingUser(List<String> sequence, String label) {
        return new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<Object, Object> intercept(
                    ConsumerRecord<Object, Object> rec, Consumer<Object, Object> consumer) {
                sequence.add(label + ":intercept");
                return rec;
            }

            @Override
            public void afterRecord(ConsumerRecord<Object, Object> rec, Consumer<Object, Object> consumer) {
                sequence.add(label + ":after");
            }
        };
    }

    private static PulseProperties defaultProperties() {
        return new PulseProperties(
                new PulseProperties.Context(
                        true,
                        "X-Request-ID",
                        "X-Correlation-ID",
                        "X-User-ID",
                        "Pulse-Tenant-Id",
                        "Idempotency-Key",
                        List.of()),
                new PulseProperties.TraceGuard(true, false, List.of()),
                new PulseProperties.Sampling(1.0, true),
                new PulseProperties.Async(true, false, 8, 32, 100, "pulse-", true),
                new PulseProperties.Kafka(true, true),
                new PulseProperties.ExceptionHandler(true),
                new PulseProperties.Cardinality(true, 1000, "OVERFLOW", List.of(), List.of()),
                new PulseProperties.TimeoutBudget(
                        true,
                        "Pulse-Timeout-Ms",
                        "Pulse-Timeout-Ms",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(30),
                        Duration.ofMillis(50),
                        Duration.ofMillis(100)),
                new PulseProperties.WideEvents(true, true, true, "pulse.events", "event"),
                new PulseProperties.Logging(true),
                new PulseProperties.Banner(true),
                new PulseProperties.Histograms(true, List.of(), List.of()),
                new PulseProperties.Slo(true, List.of()),
                new PulseProperties.Health(true, Duration.ofMinutes(5)),
                new PulseProperties.Shutdown(
                        true, Duration.ofSeconds(10), new PulseProperties.Shutdown.Drain(true, Duration.ofSeconds(30))),
                new PulseProperties.Jobs(true, true, Duration.ofHours(1)),
                new PulseProperties.Db(true, 50, Duration.ofMillis(500)),
                new PulseProperties.Resilience(true),
                new PulseProperties.Profiling(true, null),
                new PulseProperties.Dependencies(
                        true,
                        java.util.Map.of(),
                        "unknown",
                        20,
                        new PulseProperties.Dependencies.Health(true, List.of(), 0.05, false)),
                new PulseProperties.Tenant(
                        true,
                        new PulseProperties.Tenant.Header(true, "Pulse-Tenant-Id"),
                        new PulseProperties.Tenant.Jwt(false, "tenant_id"),
                        new PulseProperties.Tenant.Subdomain(false, 0),
                        100,
                        "__overflow__",
                        "unknown",
                        List.of()),
                new PulseProperties.Retry(true, "Pulse-Retry-Depth", 3),
                new PulseProperties.Priority(true, "Pulse-Priority", "normal", true, List.of()),
                new PulseProperties.ContainerMemory(true, true, 0.10, "/sys/fs/cgroup"),
                new PulseProperties.OpenFeature(true),
                new PulseProperties.Cache(new PulseProperties.Cache.Caffeine(true)));
    }
}
