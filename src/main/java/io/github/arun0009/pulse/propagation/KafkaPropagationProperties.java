package io.github.arun0009.pulse.propagation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

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
 *
 * <p>{@link #skipStaleRecords()} is the <em>event-freshness</em> clock, independent of the HTTP
 * timeout budget. A 202 Accepted handler that publishes "charge the card later" must not drop
 * that event just because the original request's 2s budget expired. Skip is opt-in: when the
 * record's Kafka timestamp is older than {@link #skipStaleMaxAge()}, the interceptor returns
 * {@code null} and Spring Kafka does not invoke the listener.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.kafka")
public record KafkaPropagationProperties(
        @DefaultValue("true") boolean propagationEnabled,
        @DefaultValue("true") boolean consumerTimeLagEnabled,
        @DefaultValue("false") boolean skipStaleRecords,
        @DefaultValue("5m") Duration skipStaleMaxAge) {

    public static KafkaPropagationProperties defaults() {
        return new KafkaPropagationProperties(true, true, false, Duration.ofMinutes(5));
    }
}
