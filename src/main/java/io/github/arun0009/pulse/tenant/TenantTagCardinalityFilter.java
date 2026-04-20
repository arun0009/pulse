package io.github.arun0009.pulse.tenant;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enforces a dedicated cardinality cap on the {@code tenant} tag, separate from the global
 * {@link io.github.arun0009.pulse.guardrails.CardinalityFirewall}. Tenants are an order of
 * magnitude higher cardinality than {@code endpoint} or {@code method}, so they get their own
 * (lower) ceiling that operators can tune without affecting the rest of the firewall.
 *
 * <p>For every meter created with a {@code tenant} tag, the distinct tenants seen for that
 * meter are tracked in a {@link Set}. Once the count exceeds {@code maxTagCardinality}, any
 * further tenant value is rewritten to {@link TenantProperties#overflowValue()} (default
 * {@code __overflow__}) and a one-time WARN line fires.
 *
 * <p>Memory bound: at most {@code (number of tenant-tagged meters) × maxTagCardinality} string
 * references. With the defaults (100 tenants × maybe a dozen tagged meters) that's a few KB.
 */
public final class TenantTagCardinalityFilter implements MeterFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantTagCardinalityFilter.class);
    private static final String TENANT_TAG = "tenant";

    private final int maxCardinality;
    private final String overflowValue;
    private final ConcurrentHashMap<String, Set<String>> tenantsByMeter = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> warnedByMeter = new ConcurrentHashMap<>();

    public TenantTagCardinalityFilter(TenantProperties config) {
        this.maxCardinality = config.maxTagCardinality();
        this.overflowValue = config.overflowValue();
    }

    @Override
    public Meter.Id map(Meter.Id id) {
        Iterable<Tag> tags = id.getTagsAsIterable();
        String tenant = null;
        for (Tag tag : tags) {
            if (TENANT_TAG.equals(tag.getKey())) {
                tenant = tag.getValue();
                break;
            }
        }
        if (tenant == null) return id;

        Set<String> known = tenantsByMeter.computeIfAbsent(id.getName(), n -> ConcurrentHashMap.newKeySet());
        if (known.contains(tenant)) return id;
        if (known.size() < maxCardinality) {
            known.add(tenant);
            return id;
        }
        warnOnce(id.getName());
        return id.replaceTags(Tags.of(replaced(tags, tenant)));
    }

    private List<Tag> replaced(Iterable<Tag> tags, String oldTenantValue) {
        List<Tag> out = new ArrayList<>();
        for (Tag tag : tags) {
            if (TENANT_TAG.equals(tag.getKey()) && oldTenantValue.equals(tag.getValue())) {
                out.add(Tag.of(TENANT_TAG, overflowValue));
            } else {
                out.add(tag);
            }
        }
        return out;
    }

    private void warnOnce(String meter) {
        AtomicBoolean flag = warnedByMeter.computeIfAbsent(meter, m -> new AtomicBoolean());
        if (flag.compareAndSet(false, true)) {
            log.warn(
                    "Pulse tenant tag exceeded {} distinct values on meter '{}'. Subsequent tenants "
                            + "are bucketed to '{}'. Either raise pulse.tenant.max-tag-cardinality "
                            + "or remove this meter from pulse.tenant.tag-meters.",
                    maxCardinality,
                    meter,
                    overflowValue);
        }
    }

    /** Exposed for diagnostics — distinct tenants seen for this meter. */
    public int distinctTenantsSeen(String meterName) {
        Set<String> set = tenantsByMeter.get(meterName);
        return set == null ? 0 : set.size();
    }
}
