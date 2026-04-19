package io.github.arun0009.pulse.profiling;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a real OpenTelemetry SDK with an in-memory exporter and asserts that the profiling
 * span processor stamps the documented attributes on every started span. The point of these
 * tests is to lock in the contract a downstream APM (Tempo, Jaeger, Grafana traces) consumes
 * — any rename of {@code profile.id}, {@code pyroscope.profile_id}, or {@code pulse.profile.url}
 * would silently break the click-through to the flame graph.
 */
class PulseProfilingSpanProcessorTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private OpenTelemetrySdk sdk;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
    }

    @AfterEach
    void tearDown() {
        if (sdk != null) sdk.close();
    }

    private void buildSdkWith(PulseProfilingSpanProcessor processor) {
        // Order matters: register the Pulse processor BEFORE the exporter so the attributes it
        // sets at start-time are present when the exporter sees the span at end-time.
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(processor)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    @Test
    void stamps_profile_id_and_pyroscope_profile_id_with_the_trace_id_on_every_span() {
        buildSdkWith(new PulseProfilingSpanProcessor("orders-svc", null));
        sdk.getTracer("test").spanBuilder("op").startSpan().end();

        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = exporter.getFinishedSpanItems().get(0);
        Attributes attrs = span.getAttributes();

        assertThat(attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("profile.id")))
                .isEqualTo(span.getTraceId());
        assertThat(attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("pyroscope.profile_id")))
                .as("Pyroscope-aware UIs read pyroscope.profile_id specifically; we keep both"
                        + " keys for parity with the agent's own auto-instrumentation.")
                .isEqualTo(span.getTraceId());
    }

    @Test
    void stamps_profile_url_only_on_root_spans_when_pyroscope_url_is_configured() {
        buildSdkWith(new PulseProfilingSpanProcessor("orders-svc", "https://pyroscope.example.com"));
        var rootSpan = sdk.getTracer("test").spanBuilder("inbound-http").startSpan();
        try {
            try (var scope = rootSpan.makeCurrent()) {
                sdk.getTracer("test").spanBuilder("child-db-call").startSpan().end();
            }
        } finally {
            rootSpan.end();
        }

        assertThat(exporter.getFinishedSpanItems()).hasSize(2);
        SpanData child = exporter.getFinishedSpanItems().get(0);
        SpanData root = exporter.getFinishedSpanItems().get(1);

        assertThat(child.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("pulse.profile.url")))
                .as("Stamping the URL on every child span would clutter every trace — root only")
                .isNull();
        String url = root.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("pulse.profile.url"));
        assertThat(url)
                .startsWith("https://pyroscope.example.com/explore?")
                .contains("trace_id=" + root.getTraceId())
                .contains("orders-svc");
    }

    @Test
    void does_not_stamp_profile_url_when_no_pyroscope_url_is_configured() {
        buildSdkWith(new PulseProfilingSpanProcessor("svc", null));
        sdk.getTracer("test").spanBuilder("op").startSpan().end();

        assertThat(exporter.getFinishedSpanItems()
                        .get(0)
                        .getAttributes()
                        .get(io.opentelemetry.api.common.AttributeKey.stringKey("pulse.profile.url")))
                .as("URL is opt-in — without pulse.profiling.pyroscope-url the trace UI shouldn't"
                        + " advertise a click-through that won't load")
                .isNull();
    }

    @Test
    void normalizes_trailing_slash_in_pyroscope_url_so_a_double_slash_is_never_emitted() {
        buildSdkWith(new PulseProfilingSpanProcessor("svc", "https://pyroscope.example.com/"));
        sdk.getTracer("test").spanBuilder("op").startSpan().end();

        String url = exporter.getFinishedSpanItems()
                .get(0)
                .getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("pulse.profile.url"));
        assertThat(url).doesNotContain(".com//explore").contains(".com/explore");
    }
}
