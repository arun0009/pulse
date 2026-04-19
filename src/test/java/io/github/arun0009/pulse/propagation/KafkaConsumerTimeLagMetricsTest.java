package io.github.arun0009.pulse.propagation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConsumerTimeLagMetricsTest {

    @Test
    void registersGaugePerTopicPartitionGroup() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaConsumerTimeLagMetrics metrics = new KafkaConsumerTimeLagMetrics(registry);

        long fiveSecondsAgo = System.currentTimeMillis() - 5_000L;
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "orders",
                3,
                42L,
                fiveSecondsAgo,
                TimestampType.CREATE_TIME,
                0,
                0,
                "k",
                "v",
                new RecordHeaders(),
                Optional.empty());

        metrics.observe(record, "order-processor");

        Gauge gauge = registry.get("pulse.kafka.consumer.time_lag")
                .tag("group", "order-processor")
                .tag("topic", "orders")
                .tag("partition", "3")
                .gauge();

        assertThat(gauge.value()).isBetween(4.5, 6.0);
    }

    @Test
    void unknownGroupTagsWhenGroupIdMissing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaConsumerTimeLagMetrics metrics = new KafkaConsumerTimeLagMetrics(registry);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "events",
                0,
                0L,
                System.currentTimeMillis(),
                TimestampType.CREATE_TIME,
                0,
                0,
                "k",
                "v",
                new RecordHeaders(),
                Optional.empty());

        metrics.observe(record, null);

        assertThat(registry.find("pulse.kafka.consumer.time_lag")
                        .tag("group", "unknown")
                        .gauge())
                .isNotNull();
    }

    @Test
    void noTimestampSkipsObservation() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaConsumerTimeLagMetrics metrics = new KafkaConsumerTimeLagMetrics(registry);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "events",
                0,
                0L,
                ConsumerRecord.NO_TIMESTAMP,
                TimestampType.NO_TIMESTAMP_TYPE,
                0,
                0,
                "k",
                "v",
                new RecordHeaders(),
                Optional.empty());

        metrics.observe(record, "g");

        assertThat(registry.find("pulse.kafka.consumer.time_lag").gauge()).isNull();
    }
}
