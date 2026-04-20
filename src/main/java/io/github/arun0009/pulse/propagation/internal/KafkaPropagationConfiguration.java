package io.github.arun0009.pulse.propagation.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.core.ContextProperties;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetProperties;
import io.github.arun0009.pulse.priority.PriorityProperties;
import io.github.arun0009.pulse.propagation.HeaderPropagation;
import io.github.arun0009.pulse.propagation.KafkaConsumerTimeLagMetrics;
import io.github.arun0009.pulse.propagation.KafkaPropagationContext;
import io.github.arun0009.pulse.propagation.KafkaPropagationProperties;
import io.github.arun0009.pulse.propagation.PulseKafkaProducerInterceptor;
import io.github.arun0009.pulse.propagation.PulseKafkaRecordInterceptor;
import io.github.arun0009.pulse.resilience.RetryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.RecordInterceptor;

import java.util.List;
import java.util.Map;

/**
 * Wires Pulse propagation into Spring Kafka.
 *
 * <ul>
 *   <li><b>Producer side</b> — registers {@link PulseKafkaProducerInterceptor} via Kafka's native
 *       {@code interceptor.classes} config so that <em>both</em> {@link KafkaTemplate} and any raw
 *       {@code KafkaProducer} created from the same factory propagate MDC + timeout budget.
 *   <li><b>Consumer side</b> — exposes a {@link RecordInterceptor} bean which Spring Kafka picks up
 *       automatically; it hydrates MDC and opens a {@code TimeoutBudget} baggage scope before the
 *       {@code @KafkaListener} method runs.
 * </ul>
 *
 * <p>Only activates when {@code pulse.kafka.propagation-enabled=true} (default) and Spring Kafka
 * is on the classpath. Trace context (W3C {@code traceparent}) is intentionally left to Spring
 * Boot's OpenTelemetry integration so we do not double-instrument it.
 *
 * <p>Bean methods live in an inner class so Spring does not introspect Kafka-typed return values
 * when {@code spring-kafka} is absent from the application classpath.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
public class KafkaPropagationConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(KafkaTemplate.class)
    @ConditionalOnProperty(
            prefix = "pulse.kafka",
            name = "propagation-enabled",
            havingValue = "true",
            matchIfMissing = true)
    static class Beans {

        @Bean
        @ConditionalOnMissingBean(name = "pulseKafkaPropagationContextInitializer")
        public KafkaPropagationContextInitializer pulseKafkaPropagationContextInitializer(
                ContextProperties context,
                RetryProperties retry,
                PriorityProperties priority,
                TimeoutBudgetProperties timeoutBudget,
                ObjectProvider<MeterRegistry> registry) {
            return new KafkaPropagationContextInitializer(
                    context, retry, priority, timeoutBudget, registry.getIfAvailable());
        }

        @Bean
        @ConditionalOnMissingBean(name = "pulseProducerFactoryCustomizer")
        @DependsOn("pulseKafkaPropagationContextInitializer")
        public ProducerFactoryCustomizer pulseProducerFactoryCustomizer() {
            return new ProducerFactoryCustomizer();
        }

        /**
         * The bare Pulse interceptor, exposed as a non-{@code @Primary} bean so applications can
         * inject it explicitly (for composition with their own custom interceptor wiring). When a
         * {@link MeterRegistry} is on the context and {@code pulse.kafka.consumer-time-lag-enabled}
         * is true (default), the interceptor also samples per-record time lag and exposes it as
         * the {@code pulse.kafka.consumer.time_lag} gauge (registered with base unit {@code seconds},
         * so Prometheus normalises it to {@code pulse_kafka_consumer_time_lag_seconds}).
         */
        @Bean
        @ConditionalOnMissingBean
        public PulseKafkaRecordInterceptor pulseKafkaRecordInterceptor(
                ContextProperties context,
                RetryProperties retry,
                PriorityProperties priority,
                TimeoutBudgetProperties timeoutBudget,
                KafkaPropagationProperties kafka,
                ObjectProvider<MeterRegistry> registryProvider) {
            MeterRegistry registry = registryProvider.getIfAvailable();
            KafkaConsumerTimeLagMetrics lag = (registry != null && kafka.consumerTimeLagEnabled())
                    ? new KafkaConsumerTimeLagMetrics(registry)
                    : null;
            return new PulseKafkaRecordInterceptor(context, retry, priority, timeoutBudget, lag);
        }

        /**
         * The composing {@code @Primary} interceptor that Spring Kafka's
         * {@code ConcurrentKafkaListenerContainerFactory} auto-wires. If the user has defined
         * <em>any</em> other {@link RecordInterceptor} bean, Pulse's interceptor is composed
         * around it (Pulse runs first on intercept, last on afterRecord) instead of replacing
         * it. With no user-defined interceptor, this is exactly the bare Pulse interceptor.
         */
        @Bean
        @Primary
        @ConditionalOnMissingBean(name = "pulseRecordInterceptorComposite")
        public RecordInterceptor<Object, Object> pulseRecordInterceptorComposite(
                PulseKafkaRecordInterceptor pulseInterceptor,
                List<RecordInterceptor<Object, Object>> userInterceptors) {
            // Filter out Pulse's own interceptor from the user list so we don't double-apply it.
            List<RecordInterceptor<Object, Object>> others = userInterceptors.stream()
                    .filter(i -> !(i instanceof PulseKafkaRecordInterceptor))
                    .toList();
            if (others.isEmpty()) {
                return pulseInterceptor;
            }
            return new CompositeRecordInterceptor(pulseInterceptor, others);
        }
    }

    /**
     * Composes Pulse's interceptor with any user-defined ones. Pulse runs first on
     * {@link #intercept(ConsumerRecord, Consumer)} so MDC + baggage are hydrated before user
     * interceptors run, and last on {@link #afterRecord(ConsumerRecord, Consumer)} so user
     * interceptors see the populated context during their cleanup.
     */
    public static final class CompositeRecordInterceptor implements RecordInterceptor<Object, Object> {

        private final PulseKafkaRecordInterceptor pulse;
        private final List<RecordInterceptor<Object, Object>> others;

        public CompositeRecordInterceptor(
                PulseKafkaRecordInterceptor pulse, List<RecordInterceptor<Object, Object>> others) {
            this.pulse = pulse;
            this.others = others;
        }

        @Override
        public @Nullable ConsumerRecord<Object, Object> intercept(
                ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
            ConsumerRecord<Object, Object> current = pulse.intercept(record, consumer);
            for (RecordInterceptor<Object, Object> other : others) {
                if (current == null) return null;
                current = other.intercept(current, consumer);
            }
            return current;
        }

        @Override
        public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
            // User interceptors first so they observe the populated MDC; Pulse cleans up last.
            for (RecordInterceptor<Object, Object> other : others) {
                try {
                    other.afterRecord(record, consumer);
                } catch (RuntimeException ignored) {
                    // Best-effort: continue to clean up Pulse state.
                }
            }
            pulse.afterRecord(record, consumer);
        }

        @Override
        public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
            pulse.success(record, consumer);
            for (RecordInterceptor<Object, Object> other : others) {
                other.success(record, consumer);
            }
        }

        @Override
        public void failure(
                ConsumerRecord<Object, Object> record, Exception exception, Consumer<Object, Object> consumer) {
            pulse.failure(record, exception, consumer);
            for (RecordInterceptor<Object, Object> other : others) {
                other.failure(record, exception, consumer);
            }
        }
    }

    /**
     * Bean whose only side effect is to populate {@link KafkaPropagationContext} on creation. The
     * static holder pattern is required because Kafka instantiates {@code ProducerInterceptor}
     * itself with a no-arg constructor and there is no way to inject Spring beans.
     */
    public static final class KafkaPropagationContextInitializer {

        public KafkaPropagationContextInitializer(
                ContextProperties context,
                RetryProperties retry,
                PriorityProperties priority,
                TimeoutBudgetProperties timeoutBudget,
                @Nullable MeterRegistry registry) {
            KafkaPropagationContext.initialize(
                    HeaderPropagation.headerToMdcKey(context, retry, priority),
                    timeoutBudget.outboundHeader(),
                    registry);
        }
    }

    /**
     * Mutates the {@code interceptor.classes} of every Spring-managed {@link
     * DefaultKafkaProducerFactory}, appending Pulse's interceptor to whatever the application
     * already configured (comma-separated, per Kafka contract).
     */
    public static final class ProducerFactoryCustomizer
            implements org.springframework.beans.factory.config.BeanPostProcessor {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof DefaultKafkaProducerFactory<?, ?> factory) {
                Map<String, Object> configs = factory.getConfigurationProperties();
                String existing = (String) configs.get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG);
                String pulseClass = PulseKafkaProducerInterceptor.class.getName();

                if (existing == null || existing.isBlank()) {
                    factory.updateConfigs(Map.of(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, pulseClass));
                } else if (!existing.contains(pulseClass)) {
                    factory.updateConfigs(
                            Map.of(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, existing + "," + pulseClass));
                }
            }
            return bean;
        }
    }
}
