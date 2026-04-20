package io.github.arun0009.pulse.guardrails;

import io.github.arun0009.pulse.enforcement.PulseEnforcementMode;
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
import java.util.function.Supplier;

/**
 * The Pulse cardinality firewall.
 *
 * <p>For each meter, tracks the distinct values seen for each tag key. Once a tag key exceeds
 * {@link CardinalityProperties#maxTagValuesPerMeter()}, any further values are rewritten to a
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
 *   <li>If {@link CardinalityProperties#meterPrefixesToProtect()} is empty, all meters are
 *       protected.
 *   <li>Meters whose name starts with any prefix in {@link
 *       CardinalityProperties#exemptMeterPrefixes()} are skipped (useful for genuinely
 *       high-cardinality business meters you've reasoned about).
 * </ul>
 *
 * <p><strong>Memory bound</strong>: the firewall keeps an in-memory set of every distinct tag
 * value it has seen, capped per {@code (meter, tag-key)} pair at {@code maxTagValuesPerMeter}.
 * Worst-case footprint is approximately
 * <pre>protected_meters × tag_keys_per_meter × maxTagValuesPerMeter × ~64 bytes/entry</pre>
 * With the default {@code maxTagValuesPerMeter=1000}, ten protected meters with five tag keys
 * each at saturation costs roughly 3 MB. Services with very large meter inventories or memory
 * constraints should lower {@code maxTagValuesPerMeter} or use
 * {@link CardinalityProperties#meterPrefixesToProtect()} to opt only the high-risk meters
 * into protection.
 */
public final class CardinalityFirewall implements MeterFilter {

    private static final Logger log = LoggerFactory.getLogger(CardinalityFirewall.class);

    private final CardinalityProperties config;
    private final PulseEnforcementMode enforcement;
    private final Supplier<MeterRegistry> registrySupplier;

    /** Cached after first resolve — Spring's {@code ObjectProvider} returns the same singleton. */
    private volatile @org.jspecify.annotations.Nullable MeterRegistry resolvedRegistry;

    /** meter-name -> tag-key -> set of values seen so far */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Set<String>>> seen = new ConcurrentHashMap<>();

    /** meter:tag combos for which we've already logged the overflow warning */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, Counter> overflowCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, LongAdder>> overflowCounts =
            new ConcurrentHashMap<>();

    /**
     * @param config firewall configuration
     * @param enforcement process-wide enforce-vs-observe gate. When {@code DRY_RUN} the firewall
     *     still counts overflow rewrites and warns on the offender, but returns the original tag
     *     value so the metric pipeline observes what would have been clamped — a low-risk way to
     *     roll the firewall into an existing fleet.
     * @param registrySupplier lazy accessor for the {@link MeterRegistry} the overflow diagnostic
     *     counter will be registered against. <strong>Must be lazy</strong> — the firewall is itself
     *     a {@link MeterFilter} resolved <em>during</em> registry construction by Spring Boot's
     *     {@code MeterRegistryPostProcessor}, so eager resolution causes a circular bean reference.
     */
    public CardinalityFirewall(
            CardinalityProperties config, PulseEnforcementMode enforcement, Supplier<MeterRegistry> registrySupplier) {
        this.config = config;
        this.enforcement = enforcement;
        this.registrySupplier = registrySupplier;
    }

    private MeterRegistry registry() {
        MeterRegistry r = resolvedRegistry;
        if (r == null) {
            r = registrySupplier.get();
            resolvedRegistry = r;
        }
        return r;
    }

    @Override
    public Meter.Id map(Meter.Id id) {
        if (!config.enabled() || !shouldProtect(id.getName())) {
            return id;
        }

        boolean dryRun = enforcement.dryRun();
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
                // Even in dry-run we record the would-have-clamped event so the operator can see
                // the impact in pulse.cardinality.overflow before flipping ENFORCING. The tag
                // value itself is left unchanged so the underlying metric is still correct.
                warnOnce(id.getName(), tag.getKey());
                countOverflow(id.getName(), tag.getKey());
                mappedValue = dryRun ? tag.getValue() : config.overflowValue();
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
        // Never protect Pulse's own diagnostics — applying the firewall to
        // pulse.cardinality.overflow would itself bucket the (meter, tag_key) tags
        // we use to surface offenders, defeating the diagnostic.
        if (meterName.startsWith("pulse.cardinality.")) return false;
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
                        .register(registry()));
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
