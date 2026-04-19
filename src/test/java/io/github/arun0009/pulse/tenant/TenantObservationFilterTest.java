package io.github.arun0009.pulse.tenant;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TenantObservationFilterTest {

    private final ObservationRegistry registry = ObservationRegistry.create();
    private final AtomicReference<KeyValues> capturedLowCardinality = new AtomicReference<>();

    TenantObservationFilterTest() {
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                capturedLowCardinality.set(context.getLowCardinalityKeyValues());
            }
        });
    }

    @org.junit.jupiter.api.BeforeEach
    void resetCapture() {
        capturedLowCardinality.set(KeyValues.empty());
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void tagsObservationWhenNamePrefixMatches() {
        register(List.of("http.server.requests"));
        TenantContext.set("acme");

        Observation.createNotStarted("http.server.requests", registry).observe(() -> {});

        assertThat(capturedLowCardinality.get().stream().anyMatch(kv -> "tenant".equals(kv.getKey())))
                .isTrue();
        assertThat(capturedLowCardinality.get().stream()
                        .filter(kv -> "tenant".equals(kv.getKey()))
                        .findFirst()
                        .orElseThrow()
                        .getValue())
                .isEqualTo("acme");
    }

    @Test
    void leavesObservationUntaggedWhenNoTenantInContext() {
        register(List.of("http.server.requests"));

        Observation.createNotStarted("http.server.requests", registry).observe(() -> {});

        assertThat(capturedLowCardinality.get().stream().anyMatch(kv -> "tenant".equals(kv.getKey())))
                .isFalse();
    }

    @Test
    void leavesUnmatchedObservationUntagged() {
        register(List.of("http.server.requests"));
        TenantContext.set("acme");

        Observation.createNotStarted("jdbc.query", registry).observe(() -> {});

        assertThat(capturedLowCardinality.get().stream().anyMatch(kv -> "tenant".equals(kv.getKey())))
                .isFalse();
    }

    private void register(List<String> tagMeters) {
        registry.observationConfig().observationFilter(new TenantObservationFilter(tagMeters));
    }
}
