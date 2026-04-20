package io.github.arun0009.pulse.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.encoder.EncoderBase;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Logback {@link ch.qos.logback.core.encoder.Encoder} that emits the same JSON shape as Pulse's
 * Log4j2 {@code pulse-json-layout.json} so consumers who prefer Logback get identical structured
 * logs without needing a third-party JSON encoder dependency.
 *
 * <p>Each event is rendered as a single-line JSON document with:
 *
 * <ul>
 *   <li>Top-level log record fields — {@code time}, {@code level}, {@code logger}, {@code thread},
 *       {@code message}, {@code exception}.
 *   <li>OpenTelemetry semantic-convention aliases — {@code trace_id}, {@code span_id},
 *       {@code service.name}, {@code service.version}, {@code deployment.environment},
 *       {@code vcs.ref.head.revision}, {@code user.id}, {@code http.request.id}.
 *   <li>Pulse flat-name fields kept for back-compat with existing dashboards — {@code traceId},
 *       {@code spanId}, {@code service}, {@code env}, {@code app.version}, {@code build.commit},
 *       {@code userId}, {@code requestId}.
 *   <li>OTel resource attributes resolved at startup — {@code host.name}, {@code container.id},
 *       {@code k8s.pod.name}, {@code k8s.namespace.name}, {@code k8s.node.name},
 *       {@code cloud.provider}, {@code cloud.region}, {@code cloud.availability_zone}. Each
 *       defaults to {@code "unknown"} when not detected on the current host.
 *   <li>{@code context} — the full MDC map, with PII-bearing values masked.
 * </ul>
 *
 * <p>The class is self-contained: it does not depend on Jackson, Gson, or
 * {@code logstash-logback-encoder}. Field count is small (~20 keys) and the JSON is hand-rendered
 * with {@link StringBuilder}, which is faster than a generic JSON encoder for this shape and
 * keeps Pulse's classpath surface narrow. PII masking is applied to the {@code message} field and
 * to MDC values via {@link PiiMaskingConverter#mask(String)}.
 *
 * <p>Wired in {@code logback-spring.xml}; consumers do not instantiate this directly.
 */
public class PulseLogbackEncoder extends EncoderBase<ILoggingEvent> {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final byte[] EMPTY = new byte[0];
    private static final byte NEWLINE = (byte) '\n';

    private static final String VERSION_SYS_PROP = "pulse.app.version";
    private static final String COMMIT_SYS_PROP = "pulse.build.commit";
    private static final String UNKNOWN = "unknown";

    /**
     * Resource-attribute system properties seeded at startup by
     * {@link PulseLoggingEnvironmentPostProcessor}. Each entry is {@code (json-key, sysprop-key)}
     * — the JSON key is the OTel semantic-convention name, the sysprop key is what
     * {@link ResourceAttributeResolver} writes. Encoded in this order on every line so the output
     * is stable across runs.
     */
    private static final String[][] RESOURCE_ATTRIBUTE_KEYS = {
        {"host.name", "pulse.host.name"},
        {"container.id", "pulse.container.id"},
        {"k8s.pod.name", "pulse.k8s.pod.name"},
        {"k8s.namespace.name", "pulse.k8s.namespace.name"},
        {"k8s.node.name", "pulse.k8s.node.name"},
        {"cloud.provider", "pulse.cloud.provider"},
        {"cloud.region", "pulse.cloud.region"},
        {"cloud.availability_zone", "pulse.cloud.availability_zone"},
    };

    @Override
    public byte[] headerBytes() {
        return EMPTY;
    }

    @Override
    public byte[] footerBytes() {
        return EMPTY;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(384);
        sb.append('{');

        appendString(sb, "time", ISO_UTC.format(Instant.ofEpochMilli(event.getTimeStamp())));
        appendString(sb, "level", event.getLevel().toString());
        appendString(sb, "logger", event.getLoggerName());
        appendString(sb, "thread", event.getThreadName());
        appendString(sb, "message", PiiMaskingConverter.mask(event.getFormattedMessage()));

        Map<String, String> mdc = readMdcSafely(event);

        // OTel semconv aliases. Both the dotted (semconv) and flat (Pulse legacy) names point
        // at the same MDC value or system property so consumers reading either name see the
        // same value. The flat names are scheduled for removal in 1.0 after a deprecation cycle.
        appendMdc(sb, "trace_id", mdc, "traceId");
        appendMdc(sb, "span_id", mdc, "spanId");
        appendMdc(sb, "traceId", mdc, "traceId");
        appendMdc(sb, "spanId", mdc, "spanId");

        appendMdc(sb, "service.name", mdc, "service");
        appendMdc(sb, "service", mdc, "service");
        appendString(sb, "service.version", System.getProperty(VERSION_SYS_PROP, UNKNOWN));
        appendString(sb, "app.version", System.getProperty(VERSION_SYS_PROP, UNKNOWN));
        appendMdc(sb, "deployment.environment", mdc, "env");
        appendMdc(sb, "env", mdc, "env");
        appendString(sb, "vcs.ref.head.revision", System.getProperty(COMMIT_SYS_PROP, UNKNOWN));
        appendString(sb, "build.commit", System.getProperty(COMMIT_SYS_PROP, UNKNOWN));

        appendMdc(sb, "user.id", mdc, "userId");
        appendMdc(sb, "userId", mdc, "userId");
        appendMdc(sb, "http.request.id", mdc, "requestId");
        appendMdc(sb, "requestId", mdc, "requestId");

        for (String[] attribute : RESOURCE_ATTRIBUTE_KEYS) {
            appendString(sb, attribute[0], System.getProperty(attribute[1], UNKNOWN));
        }

        appendContext(sb, mdc);

        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            appendString(sb, "exception", renderStackTrace(throwable));
        }

        // Trim trailing comma if any, then close.
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.setLength(sb.length() - 1);
        }
        sb.append('}');

        byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[payload.length + 1];
        System.arraycopy(payload, 0, out, 0, payload.length);
        out[payload.length] = NEWLINE;
        return out;
    }

    /**
     * Reads the MDC map from the event without propagating an NPE when SLF4J's
     * {@link org.slf4j.spi.MDCAdapter} is unavailable (which can happen during very-early app
     * startup, in tests with a misconfigured SLF4J binding, or in environments where multiple
     * SLF4J providers race to claim the static binding). A missing MDC is never worth dropping
     * a log line over — we'd rather emit the line with no context fields than swallow it.
     */
    private static Map<String, String> readMdcSafely(ILoggingEvent event) {
        try {
            Map<String, String> map = event.getMDCPropertyMap();
            return map != null ? map : Map.of();
        } catch (NullPointerException npe) {
            return Map.of();
        }
    }

    private static void appendString(StringBuilder sb, String key, String value) {
        if (value == null) return;
        sb.append('"').append(escape(key)).append("\":\"").append(escape(value)).append("\",");
    }

    private static void appendMdc(StringBuilder sb, String key, Map<String, String> mdc, String mdcKey) {
        String value = mdc == null ? null : mdc.get(mdcKey);
        if (value == null || value.isEmpty()) return;
        appendString(sb, key, PiiMaskingConverter.mask(value));
    }

    /**
     * Renders the full MDC map under a {@code context} object so consumers who add custom MDC keys
     * (via {@link io.github.arun0009.pulse.core.ContextContributor}) see them on every line. PII
     * masking is applied to every value defensively.
     */
    private static void appendContext(StringBuilder sb, Map<String, String> mdc) {
        if (mdc == null || mdc.isEmpty()) return;
        sb.append("\"context\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : mdc.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
            if (!first) sb.append(',');
            sb.append('"')
                    .append(escape(entry.getKey()))
                    .append("\":\"")
                    .append(escape(PiiMaskingConverter.mask(entry.getValue())))
                    .append('"');
            first = false;
        }
        sb.append("},");
    }

    /**
     * Writes a stack trace using Logback's own throwable-rendering helper so the format matches
     * what consumers see in plain-text Logback configurations.
     */
    private static String renderStackTrace(IThrowableProxy throwable) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(throwable.getClassName());
        if (throwable.getMessage() != null) {
            sb.append(": ").append(throwable.getMessage());
        }
        for (StackTraceElementProxy element : throwable.getStackTraceElementProxyArray()) {
            sb.append('\n').append('\t').append(element.toString());
        }
        IThrowableProxy cause = throwable.getCause();
        if (cause != null) {
            sb.append("\nCaused by: ").append(renderStackTrace(cause));
        }
        return sb.toString();
    }

    /**
     * Minimal JSON string escaping — handles the characters required by RFC 8259 §7. Performance
     * matters here: this runs on every log line. Avoids the cost of a full {@code Pattern}
     * compilation by walking the string once.
     */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String replacement = null;
            switch (c) {
                case '"' -> replacement = "\\\"";
                case '\\' -> replacement = "\\\\";
                case '\n' -> replacement = "\\n";
                case '\r' -> replacement = "\\r";
                case '\t' -> replacement = "\\t";
                case '\b' -> replacement = "\\b";
                case '\f' -> replacement = "\\f";
                default -> {
                    if (c < 0x20) {
                        replacement = String.format("\\u%04x", (int) c);
                    }
                }
            }
            if (replacement != null) {
                if (out == null) {
                    out = new StringBuilder(s.length() + 16);
                    out.append(s, 0, i);
                }
                out.append(replacement);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? s : out.toString();
    }
}
