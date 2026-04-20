package io.github.arun0009.pulse.guardrails;

import io.github.arun0009.pulse.enforcement.PulseEnforcementMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cardinality firewall is the most quoted Pulse feature in the README, so its behavior is
 * asserted against the four cases that matter in production: (1) low-cardinality counters pass
 * through untouched, (2) the limit is honored exactly per meter:tag, (3) overflow values funnel
 * into a single OVERFLOW bucket so total counts stay accurate, (4) other meters are unaffected.
 */
class CardinalityFirewallTest {

    private static final CardinalityProperties DEFAULT_CONFIG =
            new CardinalityProperties(true, 1000, "OVERFLOW", List.of(), List.of());

    private static PulseEnforcementMode enforcing() {
        return new PulseEnforcementMode(PulseEnforcementMode.Mode.ENFORCING);
    }

    @Test
    void low_cardinality_tags_pass_through_unchanged() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new CardinalityFirewall(DEFAULT_CONFIG, enforcing(), () -> registry));

        for (int i = 0; i < 50; i++) {
            registry.counter("orders.placed", "region", "us-east-" + (i % 4)).increment();
        }

        assertThat(registry.find("orders.placed").counters())
                .hasSize(4)
                .allSatisfy(c -> assertThat(c.getId().getTag("region")).doesNotContain("OVERFLOW"));
    }

    @Test
    void runaway_tag_is_capped_at_configured_limit_and_excess_buckets_to_overflow() {
        CardinalityProperties config = new CardinalityProperties(true, 100, "OVERFLOW", List.of(), List.of());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new CardinalityFirewall(config, enforcing(), () -> registry));

        for (int i = 0; i < 250; i++) {
            registry.counter("orders.placed", "userId", "user-" + i).increment();
        }

        long distinctNonOverflow = registry.find("orders.placed").counters().stream()
                .map(c -> c.getId().getTag("userId"))
                .filter(v -> !"OVERFLOW".equals(v))
                .distinct()
                .count();
        assertThat(distinctNonOverflow).isEqualTo(100);

        double overflowTotal = registry.find("orders.placed")
                .tag("userId", "OVERFLOW")
                .counter()
                .count();
        assertThat(overflowTotal).isEqualTo(150.0);

        double grandTotal = registry.find("orders.placed").counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
        assertThat(grandTotal)
                .as("total event count must be preserved end-to-end")
                .isEqualTo(250.0);

        assertThat(registry.find("pulse.cardinality.overflow")
                        .tag("meter", "orders.placed")
                        .tag("tag_key", "userId")
                        .counter())
                .isNotNull();
    }

    @Test
    void exempt_meter_prefix_is_not_protected() {
        CardinalityProperties config = new CardinalityProperties(true, 10, "OVERFLOW", List.of(), List.of("business."));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new CardinalityFirewall(config, enforcing(), () -> registry));

        for (int i = 0; i < 50; i++) {
            registry.counter("business.events", "id", "evt-" + i).increment();
        }

        long distinct = registry.find("business.events").counters().stream()
                .map(c -> c.getId().getTag("id"))
                .filter(v -> !"OVERFLOW".equals(v))
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(50);
    }

    @Test
    void allowlist_protects_only_named_prefixes() {
        CardinalityProperties config = new CardinalityProperties(true, 5, "OVERFLOW", List.of("http."), List.of());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new CardinalityFirewall(config, enforcing(), () -> registry));

        for (int i = 0; i < 20; i++) {
            registry.counter("http.requests", "uri", "/u/" + i).increment();
            registry.counter("custom.metric", "id", "v-" + i).increment();
        }

        long httpDistinct = registry.find("http.requests").counters().stream()
                .map(c -> c.getId().getTag("uri"))
                .filter(v -> !"OVERFLOW".equals(v))
                .distinct()
                .count();
        assertThat(httpDistinct).isEqualTo(5);

        long customDistinct = registry.find("custom.metric").counters().stream()
                .map(c -> c.getId().getTag("id"))
                .distinct()
                .count();
        assertThat(customDistinct).isEqualTo(20);
    }

    @Test
    void disabled_firewall_is_a_no_op() {
        CardinalityProperties off = new CardinalityProperties(false, 1, "OVERFLOW", List.of(), List.of());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new CardinalityFirewall(off, enforcing(), () -> registry));

        for (int i = 0; i < 30; i++) {
            registry.counter("anything", "k", "v-" + i).increment();
        }

        assertThat(registry.find("anything").counters()).hasSize(30);
    }
}
