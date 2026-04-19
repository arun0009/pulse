package io.github.arun0009.pulse.test;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test configuration that supplies an in-memory OpenTelemetry SDK so that spans and span events can
 * be asserted against without a backing exporter. Wired by the {@link PulseTest} annotation.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PulseTestConfiguration {

    @Bean
    public InMemorySpanExporter pulseTestSpanExporter() {
        return InMemorySpanExporter.create();
    }

    @Bean
    @Primary
    public OpenTelemetry pulseTestOpenTelemetry(InMemorySpanExporter exporter) {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    @Bean
    public PulseTestHarness pulseTestHarness(
            InMemorySpanExporter spanExporter, io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new PulseTestHarness(spanExporter, meterRegistry);
    }
}
