package io.github.arun0009.pulse.actuator;

import io.github.arun0009.pulse.slo.SloRuleGenerator;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /actuator/pulseui} — a one-page, dependency-free HTML view of the same data
 * {@link PulseEndpoint} returns as JSON.
 *
 * <p>The intent is "open this in your browser to see what Pulse is doing on this instance" —
 * no Grafana, no Prometheus, no kubectl required for the first 30 seconds of triage. This is
 * deliberately a separate endpoint id so the canonical {@code /actuator/pulse} stays a clean
 * machine-readable surface.
 */
@WebEndpoint(id = "pulseui")
public class PulseUiEndpoint {

    private final PulseDiagnostics diagnostics;
    private final @Nullable SloRuleGenerator sloRules;

    public PulseUiEndpoint(PulseDiagnostics diagnostics, @Nullable SloRuleGenerator sloRules) {
        this.diagnostics = diagnostics;
        this.sloRules = sloRules;
    }

    @ReadOperation(produces = "text/html")
    public String html() {
        Map<String, Object> snap = diagnostics.snapshot();
        StringBuilder html = new StringBuilder(8192);
        html.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>Pulse — ")
                .append(escape(String.valueOf(snap.get("service"))))
                .append("</title>")
                .append("<style>")
                .append(
                        "body{font:14px/1.5 system-ui,-apple-system,Segoe UI,sans-serif;margin:0;padding:24px;background:#0e1116;color:#e6edf3}")
                .append("h1{margin:0 0 4px;font-weight:600}")
                .append("h2{margin:32px 0 8px;font-weight:600;color:#79c0ff}")
                .append(".meta{color:#8b949e;margin-bottom:24px}")
                .append(
                        "table{border-collapse:collapse;width:100%;background:#161b22;border-radius:6px;overflow:hidden}")
                .append("th,td{padding:8px 12px;text-align:left;border-bottom:1px solid #21262d;vertical-align:top}")
                .append("th{background:#21262d;font-weight:600}")
                .append(".on{color:#3fb950;font-weight:600}")
                .append(".off{color:#8b949e}")
                .append(
                        ".pill{display:inline-block;padding:2px 8px;border-radius:10px;font-size:12px;background:#21262d}")
                .append(".pill.on{background:#0d4429;color:#3fb950}")
                .append(".pill.off{background:#21262d;color:#8b949e}")
                .append(
                        "pre{background:#161b22;padding:16px;border-radius:6px;overflow-x:auto;font:12px/1.5 ui-monospace,Menlo,monospace}")
                .append(
                        "code{background:#21262d;padding:1px 6px;border-radius:4px;font:12px ui-monospace,Menlo,monospace}")
                .append("a{color:#79c0ff;text-decoration:none}a:hover{text-decoration:underline}")
                .append("</style></head><body>");

        html.append("<h1>Pulse — ")
                .append(escape(String.valueOf(snap.get("service"))))
                .append("</h1>");
        html.append("<div class=\"meta\">version <code>")
                .append(escape(String.valueOf(snap.get("pulse.version"))))
                .append("</code> · env <code>")
                .append(escape(String.valueOf(snap.get("environment"))))
                .append("</code> · ")
                .append("<a href=\"./pulse\">raw json</a> · ")
                .append("<a href=\"./pulse/effective-config\">effective config</a> · ")
                .append("<a href=\"./pulse/runtime\">runtime</a> · ")
                .append("<a href=\"./pulse/slo\">slo rules (yaml)</a>")
                .append("</div>");

        html.append("<h2>Subsystems</h2>");
        html.append("<table><tr><th>Subsystem</th><th>State</th><th>Config</th></tr>");
        @SuppressWarnings("unchecked")
        Map<String, Object> subsystems = (Map<String, Object>) snap.getOrDefault("subsystems", Map.of());
        for (Map.Entry<String, Object> e : subsystems.entrySet()) {
            html.append("<tr><td><code>").append(escape(e.getKey())).append("</code></td>");
            renderSubsystemValue(html, e.getValue());
            html.append("</tr>");
        }
        html.append("</table>");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> userFeatures =
                (List<Map<String, Object>>) snap.getOrDefault("userFeatures", List.of());
        if (!userFeatures.isEmpty()) {
            html.append("<h2>User features</h2>");
            html.append("<table><tr><th>Id</th><th>State</th><th>Description</th></tr>");
            for (Map<String, Object> row : userFeatures) {
                Object en = row.get("enabled");
                boolean on = en instanceof Boolean b && b;
                html.append("<tr><td><code>")
                        .append(escape(String.valueOf(row.get("id"))))
                        .append("</code><br><span class=\"meta\">")
                        .append(escape(String.valueOf(row.get("displayName"))))
                        .append("</span></td>");
                html.append("<td><span class=\"pill ")
                        .append(on ? "on" : "off")
                        .append("\">")
                        .append(on ? "on" : "off")
                        .append("</span></td>");
                html.append("<td>")
                        .append(escape(String.valueOf(row.get("description"))))
                        .append("</td></tr>");
            }
            html.append("</table>");
        }

        html.append("<h2>Runtime</h2>");
        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) snap.getOrDefault("runtime", Map.of());
        html.append("<table><tr><th>Component</th><th>State</th><th>Details</th></tr>");
        for (Map.Entry<String, Object> e : runtime.entrySet()) {
            html.append("<tr><td><code>").append(escape(e.getKey())).append("</code></td>");
            renderSubsystemValue(html, e.getValue());
            html.append("</tr>");
        }
        html.append("</table>");

        html.append("<h2>Effective config</h2>");
        @SuppressWarnings("unchecked")
        Map<String, Object> effectiveConfig = (Map<String, Object>) snap.getOrDefault("effectiveConfig", Map.of());
        html.append("<pre>").append(escape(String.valueOf(effectiveConfig))).append("</pre>");

        html.append("<h2>SLOs</h2>");
        String yaml = sloRules == null ? "# SLO subsystem disabled (pulse.slo.enabled=false)\n" : sloRules.render();
        html.append("<pre>").append(escape(yaml)).append("</pre>");

        html.append("<h2>How to apply</h2>");
        html.append("<pre>curl -s http://&lt;host&gt;/actuator/pulse/slo \\\n  | kubectl apply -f -</pre>");

        html.append("</body></html>");
        return html.toString();
    }

    @SuppressWarnings("unchecked")
    private static void renderSubsystemValue(StringBuilder html, Object value) {
        if (value instanceof Map<?, ?> m) {
            Object enabled = m.get("enabled");
            Object config = m.get("config");
            if (enabled instanceof Boolean b) {
                html.append("<td><span class=\"pill ")
                        .append(b ? "on" : "off")
                        .append("\">")
                        .append(b ? "on" : "off")
                        .append("</span></td>");
            } else {
                // sampling, logging — no enabled flag
                html.append("<td><span class=\"pill\">—</span></td>");
            }
            html.append("<td>");
            if (config instanceof Map) {
                renderConfigMap(html, (Map<String, Object>) config);
            } else {
                renderConfigMap(html, (Map<String, Object>) m);
            }
            html.append("</td>");
        } else {
            html.append("<td>—</td><td>").append(escape(String.valueOf(value))).append("</td>");
        }
    }

    private static void renderConfigMap(StringBuilder html, Map<String, Object> config) {
        boolean first = true;
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if ("enabled".equals(entry.getKey()) || "config".equals(entry.getKey())) continue;
            if (!first) html.append("<br>");
            first = false;
            html.append("<code>").append(escape(entry.getKey())).append("</code>=");
            html.append(formatVal(entry.getValue()));
        }
    }

    private static String formatVal(Object v) {
        if (v instanceof List<?> list) {
            if (list.isEmpty()) return "<span class=\"off\">[]</span>";
            return escape(list.toString());
        }
        return escape(String.valueOf(v));
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
