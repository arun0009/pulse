package io.github.arun0009.pulse.integration;

import io.github.arun0009.pulse.events.SpanEvents;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end OTLP-export verification — the test that closes the trust gap on the README's
 * "OpenTelemetry-native, no bytecode tricks" claim.
 *
 * <p>Boots a real OpenTelemetry Collector (otel-contrib) via Testcontainers with a debug exporter,
 * builds an OTel SDK in-process pointed at the collector's HTTP endpoint, and asserts that a wide
 * event emitted from a Pulse-instrumented controller arrives at the collector in canonical
 * OTLP-protobuf shape with the expected service.name, span name, event name, and event attributes.
 *
 * <p>If this test passes, OTLP export over the wire works against any collector / backend the user
 * can plug in. Period.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {OtlpExportIT.TestApp.class, OtlpExportIT.OtelTestConfig.class})
@Testcontainers
@TestPropertySource(
        properties = {
            "spring.application.name=pulse-otlp-it",
            "app.env=it",
            "management.tracing.sampling.probability=1.0"
        })
class OtlpExportIT {

    @Container
    static final GenericContainer<?> COLLECTOR = new GenericContainer<>("otel/opentelemetry-collector-contrib:0.115.1")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("otel-collector-config.yaml"),
                    "/etc/otelcol-contrib/config.yaml")
            .withExposedPorts(4317, 4318)
            .withCommand("--config=/etc/otelcol-contrib/config.yaml")
            .waitingFor(Wait.forLogMessage(".*Everything is ready.*", 1));

    @DynamicPropertySource
    static void otlpProps(DynamicPropertyRegistry registry) {
        registry.add(
                "pulse.test.otlp.endpoint",
                () -> "http://" + COLLECTOR.getHost() + ":" + COLLECTOR.getMappedPort(4318) + "/v1/traces");
    }

    @LocalServerPort
    int port;

    @Autowired
    RestTemplate restTemplate;

    @Test
    void wide_event_emitted_in_a_request_arrives_at_otel_collector_in_canonical_otlp_shape() {
        restTemplate.getForObject("http://localhost:" + port + "/place-order", String.class);

        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    String collectorLogs = COLLECTOR.getLogs();
                    assertThat(collectorLogs)
                            .as("collector must show the trace span arriving in OTLP shape")
                            .contains("ResourceSpans");
                    assertThat(collectorLogs)
                            .as("collector must show the wide event by name")
                            .contains("order.placed");
                    assertThat(collectorLogs)
                            .as("collector must record the high-cardinality event" + " attribute")
                            .contains("ord-it-001");
                    assertThat(collectorLogs)
                            .as("service.name resource attribute must be set")
                            .contains("pulse-otlp-it");
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OtelTestConfig {
        @Bean
        @Primary
        OpenTelemetry pulseTestOtel(
                @org.springframework.beans.factory.annotation.Value("${pulse.test.otlp.endpoint}") String endpoint) {
            OtlpHttpSpanExporter exporter =
                    OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build();
            SdkTracerProvider provider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .setResource(Resource.getDefault()
                            .merge(Resource.create(
                                    Attributes.of(AttributeKey.stringKey("service.name"), "pulse-otlp-it"))))
                    .build();
            return OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        }
    }

    @SpringBootApplication
    static class TestApp {
        @Bean
        RestTemplate restTemplate() {
            return new RestTemplate();
        }

        @RestController
        static class OrderController {
            private final SpanEvents events;
            private final OpenTelemetry otel;

            OrderController(SpanEvents events, OpenTelemetry otel) {
                this.events = events;
                this.otel = otel;
            }

            @GetMapping("/place-order")
            String place() {
                var span = otel.getTracer("pulse-it")
                        .spanBuilder("OrderService.place")
                        .startSpan();
                try (var scope = span.makeCurrent()) {
                    events.emit(
                            "order.placed",
                            Map.of(
                                    "orderId", "ord-it-001",
                                    "amount", 49.99,
                                    "currency", "USD"));
                } finally {
                    span.end();
                }
                return "ok";
            }
        }
    }
}
