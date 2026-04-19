package io.github.arun0009.pulse.propagation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the wall-clock age of the most recently consumed Kafka record per
 * {@code (group, topic, partition)} as the {@code pulse.kafka.consumer.time_lag} gauge
 * (base unit {@code seconds}).
 *
 * <p>Kafka's native consumer-lag metric is reported in <em>messages</em>, which is meaningless
 * without knowing the producer rate ("you're 50,000 messages behind" can be 30 seconds or 30
 * minutes). The actionable signal is time: <em>the oldest unprocessed message is N seconds old</em>.
 * This class samples that signal cheaply by computing {@code now() - record.timestamp()} on every
 * {@link #observe} call. The value is per partition because rebalances and uneven load can leave a
 * subset of partitions arbitrarily behind even when the consumer-group as a whole looks healthy.
 *
 * <p>Cardinality is bounded by the consumer's partition assignment — Kafka itself caps it. The
 * {@code group} tag falls back to {@code "unknown"} when the {@code Consumer} does not expose its
 * {@code group.id} (e.g. simple-consumer assignments), keeping the dimension stable across
 * rebalances within the same JVM.
 */
public final class KafkaConsumerTimeLagMetrics {

    static final String METRIC_NAME = "pulse.kafka.consumer.time_lag";
    static final String UNKNOWN_GROUP = "unknown";

    private final MeterRegistry registry;
    private final ConcurrentHashMap<Key, AtomicLong> latestLagMillis = new ConcurrentHashMap<>();

    public KafkaConsumerTimeLagMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Records the lag for {@code record} as observed by the consumer in {@code groupId}. Safe to
     * call from the listener thread on every record; the per-key gauge is registered lazily so
     * the first observation registers the meter and subsequent observations are a single map
     * lookup + atomic store.
     */
    public void observe(ConsumerRecord<?, ?> record, String groupId) {
        long timestamp = record.timestamp();
        if (timestamp <= 0L) {
            return; // No producer-side or LogAppendTime — lag would be a nonsense large number.
        }
        long lag = Math.max(0L, System.currentTimeMillis() - timestamp);
        Key key = new Key(groupId == null ? UNKNOWN_GROUP : groupId, record.topic(), record.partition());
        AtomicLong holder = latestLagMillis.computeIfAbsent(key, this::registerGauge);
        holder.set(lag);
    }

    static String groupOf(Map<String, Object> consumerConfigs) {
        Object id = consumerConfigs == null ? null : consumerConfigs.get(ConsumerConfig.GROUP_ID_CONFIG);
        return id == null ? UNKNOWN_GROUP : id.toString();
    }

    private AtomicLong registerGauge(Key key) {
        AtomicLong holder = new AtomicLong();
        Gauge.builder(METRIC_NAME, holder, h -> h.get() / 1000.0)
                .description("Wall-clock age of the most recently consumed Kafka record")
                .baseUnit("seconds")
                .tags(Tags.of(
                        "group", key.group(),
                        "topic", key.topic(),
                        "partition", Integer.toString(key.partition())))
                .register(registry);
        return holder;
    }

    private record Key(String group, String topic, int partition) {}
}
