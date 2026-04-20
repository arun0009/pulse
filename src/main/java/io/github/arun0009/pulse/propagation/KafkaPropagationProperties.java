package io.github.arun0009.pulse.propagation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Kafka producer/consumer integration.
 *
 * <p>{@link #propagationEnabled()} controls registration of Pulse's producer/consumer record
 * interceptors that mirror MDC + timeout-budget + retry-depth onto record headers (and back
 * out on the consumer side).
 *
 * <p>{@link #consumerTimeLagEnabled()} turns on the time-based consumer-lag gauge. Kafka's
 * native lag metric is reported in <em>messages</em>, which is meaningless without knowing
 * the production rate. Pulse measures {@code now() - record.timestamp()} for every record
 * processed and exposes it as the
 * {@code pulse.kafka.consumer.time_lag{topic, partition, group}} gauge.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.kafka")
public record KafkaPropagationProperties(
        @DefaultValue("true") boolean propagationEnabled,
        @DefaultValue("true") boolean consumerTimeLagEnabled) {}
