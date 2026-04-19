package io.github.arun0009.pulse.guardrails;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * The Pulse cardinality firewall.
 *
 * <p>For each meter, tracks the distinct values seen for each tag key. Once a tag key exceeds
 * {@link PulseProperties.Cardinality#maxTagValuesPerMeter()}, any further values are rewritten to a
 * single {@code OVERFLOW} bucket. A one-time WARN log line fires for the offending {@code
 * meter:tag} combination so operators learn about the runaway tag without log spam.
 *
 * <p>This is registered as a Micrometer {@link MeterFilter} on the global {@code MeterRegistry}, so
 * it intercepts every {@code Meter.Id} <em>before</em> a meter is registered. The original meter
 * family is preserved — only the specific runaway tag value is bucketed, so the rest of your
 * instrumentation keeps working.
 *
 * <p>Why this matters: a single mistakenly-tagged {@code userId} or trace id can register millions
 * of unique time series in your metrics backend overnight and 10× the bill. The firewall caps the
 * blast radius at the source.
 *
 * <p>Scope:
 *
 * <ul>
 *   <li>If {@link PulseProperties.Cardinality#meterPrefixesToProtect()} is empty, all meters are
 *       protected.
 *   <li>Meters whose name starts with any prefix in {@link
 *       PulseProperties.Cardinality#exemptMeterPrefixes()} are skipped (useful for genuinely
 *       high-cardinality business meters you've reasoned about).
 * </ul>
 */
public final class CardinalityFirewall implements MeterFilter {

    private static final Logger log = LoggerFactory.getLogger(CardinalityFirewall.class);

    private final PulseProperties.Cardinality config;
    private final MeterRegistry registry;

    /** meter-name -> tag-key -> set of values seen so far */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Set<String>>> seen = new ConcurrentHashMap<>();

    /** meter:tag combos for which we've already logged the overflow warning */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, Counter> overflowCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, LongAdder>> overflowCounts =
            new ConcurrentHashMap<>();

    public CardinalityFirewall(PulseProperties.Cardinality config, MeterRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    @Override
    public Meter.Id map(Meter.Id id) {
        if (!config.enabled() || !shouldProtect(id.getName())) {
            return id;
        }

        Iterable<Tag> originalTags = id.getTagsAsIterable();
        List<Tag> rewritten = null;
        ConcurrentHashMap<String, Set<String>> tagsForMeter =
                seen.computeIfAbsent(id.getName(), n -> new ConcurrentHashMap<>());

        for (Tag tag : originalTags) {
            Set<String> values = tagsForMeter.computeIfAbsent(tag.getKey(), k -> ConcurrentHashMap.newKeySet());
            String mappedValue;
            if (values.contains(tag.getValue())) {
                mappedValue = tag.getValue();
            } else if (values.size() < config.maxTagValuesPerMeter()) {
                values.add(tag.getValue());
                mappedValue = tag.getValue();
            } else {
                mappedValue = config.overflowValue();
                warnOnce(id.getName(), tag.getKey());
                countOverflow(id.getName(), tag.getKey());
            }

            if (!mappedValue.equals(tag.getValue())) {
                if (rewritten == null) {
                    rewritten = copyTags(originalTags);
                }
                replaceTag(rewritten, tag.getKey(), mappedValue);
            }
        }

        return rewritten == null ? id : id.replaceTags(Tags.of(rewritten));
    }

    private boolean shouldProtect(String meterName) {
        for (String exempt : config.exemptMeterPrefixes()) {
            if (meterName.startsWith(exempt)) return false;
        }
        if (config.meterPrefixesToProtect().isEmpty()) return true;
        for (String protect : config.meterPrefixesToProtect()) {
            if (meterName.startsWith(protect)) return true;
        }
        return false;
    }

    private void warnOnce(String meterName, String tagKey) {
        String key = meterName + ":" + tagKey;
        if (warned.add(key)) {
            log.warn(
                    "Pulse cardinality firewall: meter '{}' exceeded {} distinct values for tag"
                            + " '{}'. Subsequent values are bucketed to '{}'. Investigate the source —"
                            + " high-cardinality tags (userIds, traceIds, request paths with ids)"
                            + " routinely 10x metrics-backend bills.",
                    meterName,
                    config.maxTagValuesPerMeter(),
                    tagKey,
                    config.overflowValue());
        }
    }

    private void countOverflow(String meterName, String tagKey) {
        overflowCounts
                .computeIfAbsent(meterName, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(tagKey, ignored -> new LongAdder())
                .increment();
        String counterKey = meterName + ":" + tagKey;
        Counter counter =
                overflowCounters.computeIfAbsent(counterKey, key -> Counter.builder("pulse.cardinality.overflow")
                        .description("Number of tag values rewritten to OVERFLOW by the Pulse cardinality firewall")
                        .tag("meter", meterName)
                        .tag("tag_key", tagKey)
                        .register(registry));
        counter.increment();
    }

    public long totalOverflowRewrites() {
        long total = 0L;
        for (Map<String, LongAdder> byTag : overflowCounts.values()) {
            for (LongAdder count : byTag.values()) {
                total += count.longValue();
            }
        }
        return total;
    }

    public List<Map<String, Object>> topOverflowingTags(int limit) {
        if (limit <= 0) return List.of();
        List<OverflowRow> rows = new ArrayList<>();
        for (Map.Entry<String, ConcurrentHashMap<String, LongAdder>> byMeter : overflowCounts.entrySet()) {
            String meter = byMeter.getKey();
            for (Map.Entry<String, LongAdder> byTag : byMeter.getValue().entrySet()) {
                String tagKey = byTag.getKey();
                rows.add(new OverflowRow(
                        meter, tagKey, byTag.getValue().longValue(), distinctValuesSeen(meter, tagKey)));
            }
        }
        rows.sort(Comparator.comparingLong(OverflowRow::overflowedValues).reversed());
        int maxRows = Math.min(rows.size(), limit);
        List<Map<String, Object>> topRows = new ArrayList<>(maxRows);
        for (int i = 0; i < maxRows; i++) {
            OverflowRow row = rows.get(i);
            topRows.add(Map.of(
                    "meter", row.meter(),
                    "tagKey", row.tagKey(),
                    "overflowedValues", row.overflowedValues(),
                    "distinctValuesSeen", row.distinctValuesSeen()));
        }
        return topRows;
    }

    private record OverflowRow(String meter, String tagKey, long overflowedValues, int distinctValuesSeen) {}

    private static List<Tag> copyTags(Iterable<Tag> source) {
        List<Tag> copy = new ArrayList<>();
        source.forEach(copy::add);
        return copy;
    }

    private static void replaceTag(List<Tag> tags, String key, String newValue) {
        for (int i = 0; i < tags.size(); i++) {
            if (tags.get(i).getKey().equals(key)) {
                tags.set(i, Tag.of(key, newValue));
                return;
            }
        }
    }

    /**
     * Test/diagnostic accessor — returns the count of distinct values seen for a meter:tag pair.
     */
    public int distinctValuesSeen(String meterName, String tagKey) {
        var meter = seen.get(meterName);
        if (meter == null) return 0;
        var values = meter.get(tagKey);
        return values == null ? 0 : values.size();
    }
}
