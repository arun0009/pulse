package io.github.arun0009.pulse.logging;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural assertions on {@code pulse-json-layout.json}.
 *
 * <p>The layout dual-emits OpenTelemetry semantic-convention field names alongside Pulse's flat
 * names so OTel-native tooling (Datadog, Honeycomb, Grafana derived fields) and existing
 * dashboards built on the flat names both work without manual relabeling. Regressions to either
 * side break a real consumer integration, so the contract is asserted here at the file level —
 * cheaper and more direct than spinning up the full Log4j2 pipeline.
 *
 * <p>Implementation note: this test deliberately reads the layout as text and uses regex rather
 * than a JSON parser. The file is owned by Pulse, its structure is fixed, and avoiding
 * {@code JsonParserFactory} sidesteps subtle differences between Jackson 2, Jackson 3, and
 * Spring's hand-rolled {@code BasicJsonParser} — none of which we want to make this test
 * depend on.
 *
 * <p>References:
 * <ul>
 *   <li>OTel logs data model — {@code trace_id}, {@code span_id}
 *   <li>OTel resource semconv 1.27+ — {@code service.name}, {@code service.version},
 *       {@code deployment.environment}
 *   <li>OTel general semconv — {@code user.id}, {@code http.request.id}
 *   <li>OTel VCS semconv (experimental) — {@code vcs.ref.head.revision}
 * </ul>
 */
class PulseJsonLayoutTemplateTest {

    private static final String TEMPLATE_PATH = "pulse-json-layout.json";

    @Test
    void emits_both_otel_semconv_and_pulse_flat_names() throws Exception {
        String json = loadTemplate();

        // OTel semconv side — required by OTel-native log consumers.
        for (String key : List.of(
                "trace_id",
                "span_id",
                "service.name",
                "service.version",
                "deployment.environment",
                "vcs.ref.head.revision",
                "user.id",
                "http.request.id")) {
            assertThat(hasTopLevelKey(json, key))
                    .as("OTel semconv field '%s' must be present in pulse-json-layout.json", key)
                    .isTrue();
        }

        // Pulse flat side — required by existing dashboards built before semconv alignment.
        for (String key :
                List.of("traceId", "spanId", "service", "env", "app.version", "build.commit", "requestId", "userId")) {
            assertThat(hasTopLevelKey(json, key))
                    .as("Pulse flat field '%s' must remain in pulse-json-layout.json for back-compat", key)
                    .isTrue();
        }
    }

    @Test
    void semconv_and_flat_aliases_resolve_to_the_same_underlying_source() throws Exception {
        String json = loadTemplate();

        // Each pair must point at the same MDC key or the same system-property pattern, otherwise
        // a log consumer reading one name and an alert reading the other would see divergent values.
        assertSameMdcKey(json, "trace_id", "traceId");
        assertSameMdcKey(json, "span_id", "spanId");
        assertSameMdcKey(json, "service.name", "service");
        assertSameMdcKey(json, "deployment.environment", "env");
        assertSameMdcKey(json, "user.id", "userId");
        assertSameMdcKey(json, "http.request.id", "requestId");

        assertSamePattern(json, "service.version", "app.version");
        assertSamePattern(json, "vcs.ref.head.revision", "build.commit");
    }

    @Test
    void preserves_top_level_log_record_fields() throws Exception {
        String json = loadTemplate();
        for (String key : List.of("time", "level", "logger", "thread", "message", "exception", "context")) {
            assertThat(hasTopLevelKey(json, key))
                    .as("Top-level log record field '%s' must remain", key)
                    .isTrue();
        }
    }

    @Test
    void emits_otel_resource_attributes_seeded_from_pulse_system_properties() throws Exception {
        String json = loadTemplate();

        // Each attribute is the OTel semconv name and resolves a ${sys:pulse.<name>:-unknown}
        // pattern that PulseLoggingEnvironmentPostProcessor seeds at startup.
        for (String attribute : List.of(
                "host.name",
                "container.id",
                "k8s.pod.name",
                "k8s.namespace.name",
                "k8s.node.name",
                "cloud.provider",
                "cloud.region",
                "cloud.availability_zone")) {
            assertThat(hasTopLevelKey(json, attribute))
                    .as("OTel resource attribute '%s' must be present in pulse-json-layout.json", attribute)
                    .isTrue();
            assertThat(patternFor(json, attribute))
                    .as("'%s' must read from the matching pulse.%s system property", attribute, attribute)
                    .isEqualTo("${sys:pulse." + attribute + ":-unknown}");
        }
    }

    private static String loadTemplate() throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(TEMPLATE_PATH)) {
            assertThat(in)
                    .as("template '%s' must be on the test classpath", TEMPLATE_PATH)
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean hasTopLevelKey(String json, String key) {
        // Top-level keys appear as `"key":` after a newline + 4 spaces of indentation in our template.
        return json.contains("\"" + key + "\":");
    }

    private static void assertSameMdcKey(String json, String semconvName, String flatName) {
        String semconvMdcKey = mdcKeyFor(json, semconvName);
        String flatMdcKey = mdcKeyFor(json, flatName);
        assertThat(semconvMdcKey)
                .as("semconv field '%s' and flat field '%s' must read from the same MDC key", semconvName, flatName)
                .isEqualTo(flatMdcKey);
    }

    private static void assertSamePattern(String json, String semconvName, String flatName) {
        String semconvPattern = patternFor(json, semconvName);
        String flatPattern = patternFor(json, flatName);
        assertThat(semconvPattern)
                .as("semconv field '%s' and flat field '%s' must resolve from the same pattern", semconvName, flatName)
                .isEqualTo(flatPattern);
    }

    /**
     * Extracts the {@code "key"} string from the resolver block immediately following the given
     * top-level field name. Tolerates whitespace and key ordering inside the block.
     */
    private static String mdcKeyFor(String json, String topLevelName) {
        return extract(
                json,
                "\"" + Pattern.quote(topLevelName)
                        + "\"\\s*:\\s*\\{[^}]*\"\\$resolver\"\\s*:\\s*\"mdc\"[^}]*\"key\"\\s*:\\s*\"([^\"]+)\"",
                topLevelName,
                "MDC key");
    }

    /**
     * Extracts the {@code "pattern"} string from the resolver block immediately following the
     * given top-level field name.
     */
    private static String patternFor(String json, String topLevelName) {
        return extract(
                json,
                "\"" + Pattern.quote(topLevelName)
                        + "\"\\s*:\\s*\\{[^}]*\"\\$resolver\"\\s*:\\s*\"pattern\"[^}]*\"pattern\"\\s*:\\s*\"([^\"]+)\"",
                topLevelName,
                "pattern");
    }

    private static String extract(String json, String regex, String topLevelName, String label) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(json);
        if (!m.find()) {
            throw new AssertionError("Could not find " + label + " for top-level field '" + topLevelName
                    + "' in pulse-json-layout.json");
        }
        return m.group(1);
    }
}
