package io.github.arun0009.pulse.tenant;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TenantTagCardinalityFilterTest {

    @Test
    void allowsTenantsBelowCap() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(filter(3));

        Counter.builder("test").tag("tenant", "a").register(registry).increment();
        Counter.builder("test").tag("tenant", "b").register(registry).increment();
        Counter.builder("test").tag("tenant", "c").register(registry).increment();

        assertThat(registry.find("test").tag("tenant", "a").counter()).isNotNull();
        assertThat(registry.find("test").tag("tenant", "c").counter()).isNotNull();
        assertThat(registry.find("test").tag("tenant", "__overflow__").counter())
                .isNull();
    }

    @Test
    void rewritesExcessTenantsToOverflowValue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(filter(2));

        Counter.builder("test").tag("tenant", "a").register(registry).increment();
        Counter.builder("test").tag("tenant", "b").register(registry).increment();
        Counter.builder("test").tag("tenant", "c").register(registry).increment();
        Counter.builder("test").tag("tenant", "d").register(registry).increment();

        assertThat(registry.find("test").tag("tenant", "__overflow__").counter())
                .isNotNull();
    }

    @Test
    void leavesUntaggedMetersAlone() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(filter(1));

        Counter.builder("untagged").register(registry).increment();

        assertThat(registry.get("untagged").counter().count()).isEqualTo(1.0);
    }

    private TenantTagCardinalityFilter filter(int max) {
        return new TenantTagCardinalityFilter(new TenantProperties(
                true,
                new TenantProperties.Header(true, "Pulse-Tenant-Id"),
                new TenantProperties.Jwt(false, "tenant_id"),
                new TenantProperties.Subdomain(false, 0),
                max,
                "__overflow__",
                "unknown",
                List.of()));
    }
}
