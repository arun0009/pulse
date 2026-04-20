package io.github.arun0009.pulse.dependencies;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyOutboundRecorderTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DependencyOutboundRecorder recorder = build(true);

    @Test
    void successfulCallRecordsRequestAndLatencyWithSuccessOutcome() {
        recorder.record("payment-service", "POST", 200, null, TimeUnit.MILLISECONDS.toNanos(120));

        assertThat(registry.get("pulse.dependency.requests")
                        .tag("dep", "payment-service")
                        .tag("method", "POST")
                        .tag("status", "200")
                        .tag("outcome", "SUCCESS")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get("pulse.dependency.latency")
                        .tag("dep", "payment-service")
                        .tag("outcome", "SUCCESS")
                        .timer()
                        .count())
                .isEqualTo(1L);
    }

    @Test
    void serverErrorMapsToServerErrorOutcome() {
        recorder.record("payment-service", "GET", 503, null, 1);

        assertThat(registry.get("pulse.dependency.requests")
                        .tag("outcome", "SERVER_ERROR")
                        .tag("status", "503")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void exceptionMapsToUnknownOutcomeAndExceptionStatus() {
        recorder.record("payment-service", "GET", -1, new IOException("boom"), 1);

        assertThat(registry.get("pulse.dependency.requests")
                        .tag("status", "exception")
                        .tag("outcome", "UNKNOWN")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void disabledRecorderEmitsNothing() {
        DependencyOutboundRecorder off = build(false);
        off.record("payment-service", "POST", 200, null, 1);

        assertThat(registry.find("pulse.dependency.requests").counters()).isEmpty();
    }

    @Test
    void recordingFeedsRequestFanoutThreadLocal() {
        RequestFanout.begin();
        try {
            recorder.record("payment-service", "GET", 200, null, 1);
            recorder.record("inventory-service", "GET", 200, null, 1);
            recorder.record("payment-service", "GET", 200, null, 1);
            RequestFanout.Snapshot snap = RequestFanout.peek();
            assertThat(snap).isNotNull();
            assertThat(snap.totalCalls()).isEqualTo(3);
            assertThat(snap.distinctDependencies()).isEqualTo(2);
        } finally {
            RequestFanout.end();
        }
    }

    private DependencyOutboundRecorder build(boolean enabled) {
        DependenciesProperties cfg = new DependenciesProperties(
                enabled,
                Map.of(),
                "unknown",
                20,
                io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties.empty(),
                new DependenciesProperties.Health(true, java.util.List.of(), 0.05, false));
        return new DependencyOutboundRecorder(registry, new DependencyResolver(cfg), cfg);
    }
}
