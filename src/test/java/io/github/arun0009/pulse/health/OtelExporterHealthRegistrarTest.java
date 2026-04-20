package io.github.arun0009.pulse.health;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

class OtelExporterHealthRegistrarTest {

    @Test
    void wrapsRawSpanExporterAndTracksIt() {
        OtelExporterHealthRegistrar registrar = new OtelExporterHealthRegistrar();
        SpanExporter raw = noopExporter();

        Object processed = registrar.postProcessAfterInitialization(raw, "spanExporter");

        assertThat(processed).isInstanceOf(LastSuccessSpanExporter.class);
        assertThat(registrar.exporters()).hasSize(1).containsExactly((LastSuccessSpanExporter) processed);
    }

    @Test
    void doesNotDoubleWrapAlreadyWrappedExporter() {
        OtelExporterHealthRegistrar registrar = new OtelExporterHealthRegistrar();
        LastSuccessSpanExporter wrapped = new LastSuccessSpanExporter(noopExporter());

        Object processed = registrar.postProcessAfterInitialization(wrapped, "spanExporter");

        assertThat(processed).isSameAs(wrapped);
        assertThat(registrar.exporters()).hasSize(1).containsExactly(wrapped);
    }

    @Test
    void leavesNonSpanExporterBeansAlone() {
        OtelExporterHealthRegistrar registrar = new OtelExporterHealthRegistrar();
        Object bean = new Object();

        Object processed = registrar.postProcessAfterInitialization(bean, "anything");

        assertThat(processed).isSameAs(bean);
        assertThat(registrar.exporters()).isEmpty();
    }

    @Test
    void exposesAllWrappedExportersInOrderObserved() {
        OtelExporterHealthRegistrar registrar = new OtelExporterHealthRegistrar();
        SpanExporter first = noopExporter();
        SpanExporter second = noopExporter();

        Object firstWrapped = registrar.postProcessAfterInitialization(first, "first");
        Object secondWrapped = registrar.postProcessAfterInitialization(second, "second");

        assertThat(registrar.exporters())
                .hasSize(2)
                .containsExactly((LastSuccessSpanExporter) firstWrapped, (LastSuccessSpanExporter) secondWrapped);
    }

    private static SpanExporter noopExporter() {
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
}
