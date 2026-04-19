package io.github.arun0009.pulse.propagation;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.RecordInterceptor;

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
@Configuration(proxyBeanMethods = false)
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
        public KafkaPropagationContextInitializer pulseKafkaPropagationContextInitializer(PulseProperties properties) {
            return new KafkaPropagationContextInitializer(properties);
        }

        @Bean
        public ProducerFactoryCustomizer pulseProducerFactoryCustomizer(
                @SuppressWarnings("unused") KafkaPropagationContextInitializer ensureInitialized) {
            return new ProducerFactoryCustomizer();
        }

        @Bean
        public RecordInterceptor<Object, Object> pulseKafkaRecordInterceptor(PulseProperties properties) {
            return new PulseKafkaRecordInterceptor(properties);
        }
    }

    /**
     * Bean whose only side effect is to populate {@link KafkaPropagationContext} on creation. The
     * static holder pattern is required because Kafka instantiates {@code ProducerInterceptor}
     * itself with a no-arg constructor and there is no way to inject Spring beans.
     */
    public static final class KafkaPropagationContextInitializer {

        public KafkaPropagationContextInitializer(PulseProperties properties) {
            KafkaPropagationContext.initialize(
                    HeaderPropagation.headerToMdcKey(properties.context()),
                    properties.timeoutBudget().outboundHeader());
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
