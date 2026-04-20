package io.github.arun0009.pulse.resilience.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.resilience.BulkheadObservation;
import io.github.arun0009.pulse.resilience.CircuitBreakerObservation;
import io.github.arun0009.pulse.resilience.RetryObservation;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Wires Pulse's Resilience4j observation beans when the corresponding registries are present.
 *
 * <p>Each observer is gated independently via {@link ConditionalOnBean} on its registry type,
 * so a service that only uses circuit breakers (no retries, no bulkheads) only pays for the
 * circuit-breaker observation. The whole configuration is gated by
 * {@link ConditionalOnClass} on the Resilience4j core types — apps that don't use Resilience4j
 * pay nothing.
 *
 * <p>The observers attach themselves via {@link org.springframework.beans.factory.SmartInitializingSingleton}
 * after all singletons are wired, so any user-declared registry — whether produced by
 * Resilience4j's own Spring Boot starter or hand-rolled — is observed without ordering
 * gymnastics in the consumer's configuration.
 */
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnClass(CircuitBreakerRegistry.class)
@ConditionalOnProperty(prefix = "pulse.resilience", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PulseResilience4jConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CircuitBreakerRegistry.class)
    public CircuitBreakerObservation pulseCircuitBreakerObservation(
            CircuitBreakerRegistry registry, MeterRegistry meterRegistry, ObjectProvider<Tracer> tracer) {
        return new CircuitBreakerObservation(registry, meterRegistry, tracer.getIfAvailable(() -> Tracer.NOOP));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RetryRegistry.class)
    @ConditionalOnClass(RetryRegistry.class)
    public RetryObservation pulseRetryObservation(
            RetryRegistry registry, MeterRegistry meterRegistry, ObjectProvider<Tracer> tracer) {
        return new RetryObservation(registry, meterRegistry, tracer.getIfAvailable(() -> Tracer.NOOP));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(BulkheadRegistry.class)
    @ConditionalOnClass(BulkheadRegistry.class)
    public BulkheadObservation pulseBulkheadObservation(
            BulkheadRegistry registry, MeterRegistry meterRegistry, ObjectProvider<Tracer> tracer) {
        return new BulkheadObservation(registry, meterRegistry, tracer.getIfAvailable(() -> Tracer.NOOP));
    }
}
