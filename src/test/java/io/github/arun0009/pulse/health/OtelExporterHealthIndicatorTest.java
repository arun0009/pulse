package io.github.arun0009.pulse.health;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OtelExporterHealthIndicatorTest {

    private static OtelExporterHealthProperties config(Duration staleAfter) {
        return new OtelExporterHealthProperties(true, staleAfter);
    }

    @Test
    void noExportersRegisteredReportsUnknown() {
        OtelExporterHealthIndicator indicator =
                new OtelExporterHealthIndicator(List.of(), config(Duration.ofMinutes(5)));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("reason", "no LastSuccessSpanExporter registered");
    }

    @Test
    void exporterThatHasNeverSucceededReportsOutOfService() {
        // Cold-started service that has never managed to emit a span — common during startup
        // before the first request, and should be distinguishable from "stuck for an hour".
        LastSuccessSpanExporter exp = new LastSuccessSpanExporter(noopExporter());

        OtelExporterHealthIndicator indicator =
                new OtelExporterHealthIndicator(List.of(exp), config(Duration.ofMinutes(5)));

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo(OtelExporterHealthIndicator.STATUS_OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsEntry("totalSuccess", 0L);
        assertThat(health.getDetails()).containsEntry("totalFailure", 0L);
    }

    @Test
    void recentSuccessReportsUp() throws Exception {
        LastSuccessSpanExporter exp = new LastSuccessSpanExporter(succeedingExporter());
        // Drive a successful export so lastSuccess is set to "now".
        exp.export(List.of()).join(1, java.util.concurrent.TimeUnit.SECONDS);

        OtelExporterHealthIndicator indicator =
                new OtelExporterHealthIndicator(List.of(exp), config(Duration.ofMinutes(5)));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat((Long) health.getDetails().get("totalSuccess")).isEqualTo(1L);
        assertThat((Long) health.getDetails().get("totalFailure")).isEqualTo(0L);
        assertThat(health.getDetails()).containsKey("lastSuccessAgeMs");
    }

    @Test
    void recordedFailuresStillReportedInDetails() throws Exception {
        LastSuccessSpanExporter ok = new LastSuccessSpanExporter(succeedingExporter());
        LastSuccessSpanExporter bad = new LastSuccessSpanExporter(failingExporter());
        ok.export(List.of()).join(1, java.util.concurrent.TimeUnit.SECONDS);
        bad.export(List.of()).join(1, java.util.concurrent.TimeUnit.SECONDS);
        bad.export(List.of()).join(1, java.util.concurrent.TimeUnit.SECONDS);

        OtelExporterHealthIndicator indicator =
                new OtelExporterHealthIndicator(List.of(ok, bad), config(Duration.ofMinutes(5)));

        Health health = indicator.health();

        // One exporter is healthy => overall UP; failures aggregated for visibility.
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat((Long) health.getDetails().get("totalSuccess")).isEqualTo(1L);
        assertThat((Long) health.getDetails().get("totalFailure")).isEqualTo(2L);
    }

    @Test
    void successOlderThanThresholdReportsDown() throws Exception {
        // Use a tiny stale window so we can prove "down on staleness" without sleeping for minutes.
        LastSuccessSpanExporter exp = new LastSuccessSpanExporter(succeedingExporter());
        exp.export(List.of()).join(1, java.util.concurrent.TimeUnit.SECONDS);
        Thread.sleep(50);

        OtelExporterHealthIndicator indicator =
                new OtelExporterHealthIndicator(List.of(exp), config(Duration.ofMillis(10)));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat((Long) health.getDetails().get("totalSuccess")).isEqualTo(1L);
    }

    private static SpanExporter noopExporter() {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return new CompletableResultCode();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
    }

    private static SpanExporter succeedingExporter() {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
    }

    private static SpanExporter failingExporter() {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return CompletableResultCode.ofFailure();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
    }
}
