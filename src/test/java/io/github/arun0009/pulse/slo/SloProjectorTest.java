package io.github.arun0009.pulse.slo;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SloProjectorTest {

    private static SloProperties.Objective availability(String name, double target) {
        return new SloProperties.Objective(name, "availability", target, null, "http.server.requests", List.of());
    }

    private static SloProperties.Objective latency(String name, double target, Duration threshold) {
        return new SloProperties.Objective(name, "latency", target, threshold, "http.server.requests", List.of());
    }

    @Test
    void availabilitySloMeetingTargetReportsMeeting() {
        // 100 requests, all 200 — should be at 100% which is above the 99.9% target.
        MeterRegistry registry = new SimpleMeterRegistry();
        Timer timer = registry.timer("http.server.requests", List.of(Tag.of("status", "200"), Tag.of("uri", "/x")));
        for (int i = 0; i < 100; i++) timer.record(Duration.ofMillis(50));

        SloProjector projector =
                new SloProjector(new SloProperties(true, List.of(availability("svc", 0.999))), registry);

        SloProjector.SloStatus status = projector.project().get(0);

        assertThat(status.name()).isEqualTo("svc");
        assertThat(status.currentRatio()).isEqualTo(1.0);
        assertThat(status.sampleCount()).isEqualTo(100);
        assertThat(status.status()).isEqualTo("meeting");
    }

    @Test
    void availabilitySloBelowTargetIsBurningFastWhenErrorsDominate() {
        // 50% error rate is catastrophic — burn = (1 - 0.5)/(1 - 0.999) = 500x, well past 14.4x.
        MeterRegistry registry = new SimpleMeterRegistry();
        Timer ok = registry.timer("http.server.requests", List.of(Tag.of("status", "200")));
        Timer fail = registry.timer("http.server.requests", List.of(Tag.of("status", "500")));
        for (int i = 0; i < 50; i++) ok.record(Duration.ofMillis(20));
        for (int i = 0; i < 50; i++) fail.record(Duration.ofMillis(20));

        SloProjector projector =
                new SloProjector(new SloProperties(true, List.of(availability("svc", 0.999))), registry);

        SloProjector.SloStatus status = projector.project().get(0);

        assertThat(status.currentRatio()).isEqualTo(0.5);
        assertThat(status.status()).isEqualTo("burning-fast");
        assertThat(status.sampleCount()).isEqualTo(100);
    }

    @Test
    void availabilityClassifiesClientErrorsAsGoodViaOutcomeTag() {
        // outcome=CLIENT_ERROR (4xx) is the caller's fault, not the service's — counted as good.
        MeterRegistry registry = new SimpleMeterRegistry();
        Timer ok =
                registry.timer("http.server.requests", List.of(Tag.of("status", "200"), Tag.of("outcome", "SUCCESS")));
        Timer client = registry.timer(
                "http.server.requests", List.of(Tag.of("status", "404"), Tag.of("outcome", "CLIENT_ERROR")));
        for (int i = 0; i < 90; i++) ok.record(Duration.ofMillis(10));
        for (int i = 0; i < 10; i++) client.record(Duration.ofMillis(5));

        SloProjector projector =
                new SloProjector(new SloProperties(true, List.of(availability("svc", 0.95))), registry);

        SloProjector.SloStatus status = projector.project().get(0);

        assertThat(status.currentRatio()).isEqualTo(1.0);
        assertThat(status.status()).isEqualTo("meeting");
    }

    @Test
    void noTimersForObjectiveReportsNoData() {
        MeterRegistry registry = new SimpleMeterRegistry();

        SloProjector projector =
                new SloProjector(new SloProperties(true, List.of(availability("svc", 0.95))), registry);

        SloProjector.SloStatus status = projector.project().get(0);

        assertThat(status.status()).isEqualTo("no data");
        assertThat(Double.isNaN(status.currentRatio())).isTrue();
        assertThat(status.sampleCount()).isZero();
    }

    @Test
    void latencyObjectiveWithoutThresholdReportsMissingThreshold() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Timer timer = registry.timer("http.server.requests", List.of(Tag.of("status", "200")));
        timer.record(Duration.ofMillis(10));

        SloProperties.Objective bad =
                new SloProperties.Objective("svc", "latency", 0.95, null, "http.server.requests", List.of());

        SloProjector projector = new SloProjector(new SloProperties(true, List.of(bad)), registry);

        assertThat(projector.project().get(0).status()).isEqualTo("missing threshold");
    }

    @Test
    void latencyObjectiveWithoutHistogramFallsBackToMeanComparison() {
        // SimpleMeterRegistry timers don't carry histogram buckets by default. The projector
        // should fall back to comparing the mean — fast service => count as good.
        MeterRegistry registry = new SimpleMeterRegistry();
        Timer timer = registry.timer("http.server.requests", List.of(Tag.of("status", "200")));
        for (int i = 0; i < 100; i++) timer.record(Duration.ofMillis(10));

        SloProjector projector = new SloProjector(
                new SloProperties(true, List.of(latency("svc", 0.95, Duration.ofMillis(500)))), registry);

        SloProjector.SloStatus status = projector.project().get(0);

        assertThat(status.currentRatio()).isEqualTo(1.0);
        assertThat(status.status()).isEqualTo("meeting");
    }

    @Test
    void sloStatusExposesItselfAsTags() {
        SloProjector.SloStatus status =
                new SloProjector.SloStatus("svc", "availability", 0.999, 0.998, 1000, "meeting");

        List<Tag> tags = status.asTags();

        assertThat(tags).extracting(Tag::getKey).containsExactly("slo", "status");
        assertThat(tags).extracting(Tag::getValue).containsExactly("svc", "meeting");
    }
}
