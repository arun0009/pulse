package io.github.arun0009.pulse.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior of {@link PulseLogbackEncoder}.
 *
 * <p>Tests construct {@link LoggingEvent} instances directly and feed them to the encoder rather
 * than going through {@link org.slf4j.LoggerFactory}. Reason: Pulse ships both {@code log4j2}
 * and {@code logback-classic} on its own test classpath (so we can build both code paths), which
 * leaves SLF4J's static binding ambiguous — calls through {@link org.slf4j.MDC} land on whichever
 * provider won the discovery race, breaking Logback's own MDC machinery in subtle ways. The
 * encoder's contract is "given an {@link ILoggingEvent} with MDC, produce this JSON" — testing
 * that contract directly is both simpler and more honest than testing the surrounding wiring.
 *
 * <p>System properties {@code pulse.app.version} and {@code pulse.build.commit} are seeded by
 * {@link PulseLoggingEnvironmentPostProcessor} in production; here we set them directly so the
 * encoder's branches are exercised.
 */
class PulseLogbackEncoderTest {

    private LoggerContext context;
    private ch.qos.logback.classic.Logger logger;
    private PulseLogbackEncoder encoder;

    @BeforeEach
    void setUp() {
        context = new LoggerContext();
        logger = context.getLogger("io.github.arun0009.pulse.logging.test");

        encoder = new PulseLogbackEncoder();
        encoder.setContext(context);
        encoder.start();

        System.setProperty("pulse.app.version", "1.2.3");
        System.setProperty("pulse.build.commit", "abc12345");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("pulse.app.version");
        System.clearProperty("pulse.build.commit");
        for (String key : RESOURCE_ATTRIBUTE_SYS_PROPS) {
            System.clearProperty(key);
        }
        context.stop();
    }

    private static final String[] RESOURCE_ATTRIBUTE_SYS_PROPS = {
        "pulse.host.name",
        "pulse.container.id",
        "pulse.k8s.pod.name",
        "pulse.k8s.namespace.name",
        "pulse.k8s.node.name",
        "pulse.cloud.provider",
        "pulse.cloud.region",
        "pulse.cloud.availability_zone",
    };

    private String encodeWith(Level level, String message, Map<String, String> mdc) {
        return encodeWith(level, message, mdc, null);
    }

    private String encodeWith(Level level, String message, Map<String, String> mdc, Throwable throwable) {
        LoggingEvent event = new LoggingEvent("fqcn", logger, level, message, throwable, new Object[0]);
        event.setTimeStamp(System.currentTimeMillis());
        if (mdc != null && !mdc.isEmpty()) {
            event.setMDCPropertyMap(mdc);
        }
        byte[] bytes = encoder.encode(event);
        // Encoder appends a trailing newline so multiple events stack into a JSON-Lines stream;
        // strip it for assertion convenience.
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }

    @Nested
    class Top_level_log_record_fields {

        @Test
        void emits_time_level_logger_thread_message() {
            String line = encodeWith(Level.INFO, "hello world", null);

            assertThat(line).contains("\"level\":\"INFO\"");
            assertThat(line).contains("\"logger\":\"io.github.arun0009.pulse.logging.test\"");
            assertThat(line).contains("\"thread\":");
            assertThat(line).contains("\"message\":\"hello world\"");
            assertThat(line).matches(".*\"time\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z\".*");
        }

        @Test
        void each_event_terminates_with_newline_so_outputs_form_a_jsonlines_stream() {
            LoggingEvent event = new LoggingEvent("fqcn", logger, Level.INFO, "x", null, new Object[0]);
            event.setTimeStamp(System.currentTimeMillis());
            byte[] bytes = encoder.encode(event);
            assertThat(bytes[bytes.length - 1]).isEqualTo((byte) '\n');
        }
    }

    @Nested
    class Otel_semconv_dual_emit {

        @Test
        void emits_both_flat_and_semconv_aliases_for_trace_context() {
            String line = encodeWith(Level.INFO, "traced", Map.of("traceId", "4c1f9e0a", "spanId", "7b2d"));

            assertThat(line).contains("\"trace_id\":\"4c1f9e0a\"");
            assertThat(line).contains("\"traceId\":\"4c1f9e0a\"");
            assertThat(line).contains("\"span_id\":\"7b2d\"");
            assertThat(line).contains("\"spanId\":\"7b2d\"");
        }

        @Test
        void emits_both_flat_and_semconv_aliases_for_service_resource_attributes() {
            String line = encodeWith(Level.INFO, "running", Map.of("service", "orders", "env", "prod"));

            assertThat(line).contains("\"service.name\":\"orders\"");
            assertThat(line).contains("\"service\":\"orders\"");
            assertThat(line).contains("\"deployment.environment\":\"prod\"");
            assertThat(line).contains("\"env\":\"prod\"");
            assertThat(line).contains("\"service.version\":\"1.2.3\"");
            assertThat(line).contains("\"app.version\":\"1.2.3\"");
            assertThat(line).contains("\"vcs.ref.head.revision\":\"abc12345\"");
            assertThat(line).contains("\"build.commit\":\"abc12345\"");
        }

        @Test
        void emits_user_id_and_request_id_under_both_names() {
            String line = encodeWith(Level.INFO, "acted", Map.of("userId", "u-42", "requestId", "r-99"));

            assertThat(line).contains("\"user.id\":\"u-42\"");
            assertThat(line).contains("\"userId\":\"u-42\"");
            assertThat(line).contains("\"http.request.id\":\"r-99\"");
            assertThat(line).contains("\"requestId\":\"r-99\"");
        }
    }

    @Nested
    class Pii_masking {

        @Test
        void masks_email_in_message() {
            String line = encodeWith(Level.INFO, "user contact: alice@example.com placed order", null);
            assertThat(line).contains("[EMAIL]");
            assertThat(line).doesNotContain("alice@example.com");
        }

        @Test
        void masks_credit_card_and_ssn_in_message() {
            String line = encodeWith(Level.INFO, "payment: 4111 1111 1111 1111 ssn: 123-45-6789", null);
            assertThat(line).contains("[CREDIT_CARD]");
            assertThat(line).contains("[SSN]");
            assertThat(line).doesNotContain("4111 1111 1111 1111");
            assertThat(line).doesNotContain("123-45-6789");
        }

        @Test
        void masks_pii_in_mdc_values_too() {
            // Defensive masking — consumers sometimes stash request payloads or user emails into
            // MDC for debugging, and we don't want those bleeding into logs unfiltered.
            String line = encodeWith(Level.INFO, "processed", Map.of("requestEmail", "bob@example.com"));
            assertThat(line).contains("[EMAIL]");
            assertThat(line).doesNotContain("bob@example.com");
        }
    }

    @Nested
    class Custom_mdc_under_context {

        @Test
        void custom_mdc_keys_appear_under_context_object() {
            String line = encodeWith(Level.INFO, "regional", Map.of("region", "us-east-1", "tenantId", "acme"));

            assertThat(line).contains("\"context\":{");
            assertThat(line).contains("\"region\":\"us-east-1\"");
            assertThat(line).contains("\"tenantId\":\"acme\"");
        }
    }

    @Nested
    class Json_safety {

        @Test
        void escapes_control_characters_and_quotes_in_message() {
            String line = encodeWith(Level.INFO, "danger: \"quoted\" \\backslash \n newline", null);
            assertThat(line).contains("\\\"quoted\\\"");
            assertThat(line).contains("\\\\backslash");
            assertThat(line).contains("\\n newline");
        }
    }

    @Nested
    class Exception_field {

        @Test
        void includes_exception_field_when_throwable_present() {
            String line = encodeWith(Level.ERROR, "boom", null, new IllegalStateException("bad-state"));

            assertThat(line).contains("\"exception\":");
            assertThat(line).contains("IllegalStateException");
            assertThat(line).contains("bad-state");
        }
    }

    @Nested
    class Resource_attribute_fields {

        @Test
        void emits_seeded_resource_attributes_under_otel_semconv_names() {
            // Production seeding happens in PulseLoggingEnvironmentPostProcessor; here we set
            // the same JVM system properties directly to assert the encoder reads them.
            System.setProperty("pulse.host.name", "ip-10-0-1-23");
            System.setProperty("pulse.container.id", "c0ffee");
            System.setProperty("pulse.k8s.pod.name", "orders-7d4b9c");
            System.setProperty("pulse.k8s.namespace.name", "checkout");
            System.setProperty("pulse.k8s.node.name", "node-7");
            System.setProperty("pulse.cloud.provider", "aws");
            System.setProperty("pulse.cloud.region", "us-east-1");
            System.setProperty("pulse.cloud.availability_zone", "us-east-1a");

            String line = encodeWith(Level.INFO, "where am i", null);

            assertThat(line).contains("\"host.name\":\"ip-10-0-1-23\"");
            assertThat(line).contains("\"container.id\":\"c0ffee\"");
            assertThat(line).contains("\"k8s.pod.name\":\"orders-7d4b9c\"");
            assertThat(line).contains("\"k8s.namespace.name\":\"checkout\"");
            assertThat(line).contains("\"k8s.node.name\":\"node-7\"");
            assertThat(line).contains("\"cloud.provider\":\"aws\"");
            assertThat(line).contains("\"cloud.region\":\"us-east-1\"");
            assertThat(line).contains("\"cloud.availability_zone\":\"us-east-1a\"");
        }

        @Test
        void unseeded_resource_attributes_default_to_unknown_so_field_is_always_present() {
            // Running on a developer laptop with no K8s/cloud env should still emit the keys —
            // operators searching for "host.name=unknown" can find unconfigured deployments.
            String line = encodeWith(Level.INFO, "minimal", null);

            assertThat(line).contains("\"host.name\":\"unknown\"");
            assertThat(line).contains("\"k8s.pod.name\":\"unknown\"");
            assertThat(line).contains("\"cloud.region\":\"unknown\"");
        }
    }
}
