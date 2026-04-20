package io.github.arun0009.pulse.fleet;

import org.jspecify.annotations.Nullable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes a stable, deterministic hash of an effective-config tree so that fleet drift can be
 * detected by comparing one hash per replica.
 *
 * <p>The hash is over a canonical string serialization where:
 *
 * <ul>
 *   <li>Map keys are sorted lexicographically.
 *   <li>Records are reflected as their accessor name → value pairs (also sorted).
 *   <li>Collections preserve their order (lists are order-significant; sets are sorted).
 *   <li>{@code null} is rendered as the literal {@code null}.
 * </ul>
 *
 * Two replicas of the same service that resolved {@code pulse.*} identically will produce the
 * same hash regardless of how Spring's relaxed binding presented the keys, regardless of insertion
 * order in maps, and regardless of JVM identity hash codes.
 */
public final class ConfigHasher {

    private ConfigHasher() {}

    /** Returns a 64-bit hex digest (truncated SHA-256) of the canonicalized tree. */
    public static String hash(Object tree) {
        StringBuilder sb = new StringBuilder(512);
        canonicalize(tree, sb);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Canonical multi-line key=value rendering. Useful for debugging two divergent hashes. */
    public static List<String> render(Object tree) {
        List<String> lines = new ArrayList<>();
        renderInto("", tree, lines);
        Collections.sort(lines);
        return lines;
    }

    private static void canonicalize(@Nullable Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            canonicalize(toSortedMap(map), sb, true);
            return;
        }
        if (value instanceof Collection<?> col) {
            // Sets have undefined iteration order — sort their string representations
            // to guarantee deterministic hashes across JVM instances and restarts.
            java.util.List<?> items = (value instanceof java.util.Set<?>)
                    ? col.stream()
                            .map(o -> o == null ? "null" : o.toString())
                            .sorted()
                            .collect(java.util.stream.Collectors.toList())
                    : new java.util.ArrayList<>(col);
            sb.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) sb.append(',');
                if (value instanceof java.util.Set<?>) {
                    // Already converted to sorted strings above.
                    sb.append('"').append(item.toString().replace("\"", "\\\"")).append('"');
                } else {
                    canonicalize(item, sb);
                }
                first = false;
            }
            sb.append(']');
            return;
        }
        if (value.getClass().isRecord()) {
            canonicalize(recordToMap(value), sb);
            return;
        }
        sb.append('"').append(value.toString().replace("\"", "\\\"")).append('"');
    }

    private static void canonicalize(Map<String, Object> sorted, StringBuilder sb, boolean braces) {
        if (braces) sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=');
            canonicalize(e.getValue(), sb);
            first = false;
        }
        if (braces) sb.append('}');
    }

    private static Map<String, Object> toSortedMap(Map<?, ?> raw) {
        Map<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            sorted.put(String.valueOf(e.getKey()), e.getValue());
        }
        return sorted;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> recordToMap(Object record) {
        Map<String, Object> map = new TreeMap<>();
        for (java.lang.reflect.RecordComponent rc : record.getClass().getRecordComponents()) {
            try {
                Object v = rc.getAccessor().invoke(record);
                map.put(rc.getName(), v);
            } catch (ReflectiveOperationException e) {
                map.put(rc.getName(), "<unreadable>");
            }
        }
        return map;
    }

    private static void renderInto(String prefix, @Nullable Object value, List<String> lines) {
        if (value == null) {
            lines.add(prefix + " = null");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<String, Object> e : toSortedMap(map).entrySet()) {
                renderInto(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), lines);
            }
            return;
        }
        if (value.getClass().isRecord()) {
            renderInto(prefix, recordToMap(value), lines);
            return;
        }
        if (value instanceof Collection<?> col) {
            int i = 0;
            for (Object item : col) {
                renderInto(prefix + "[" + i++ + "]", item, lines);
            }
            return;
        }
        lines.add(prefix + " = " + value);
    }

    /** Convenience: build a {@code {key → leaf-value}} map for the {@code config-hash} endpoint. */
    public static Map<String, String> flatten(Object tree) {
        List<String> rendered = render(tree);
        Map<String, String> flat = new LinkedHashMap<>();
        for (String line : rendered) {
            int eq = line.indexOf(" = ");
            if (eq > 0) flat.put(line.substring(0, eq), line.substring(eq + 3));
        }
        return flat;
    }
}
