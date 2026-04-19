package io.github.arun0009.pulse.core;

import org.jspecify.annotations.Nullable;

/**
 * Tiny sanitizer for user-controlled strings that flow into log messages.
 *
 * <p>Pulse occasionally needs to log values that originated from an untrusted source — typically
 * the request URI, HTTP method, or a malformed inbound header. Without sanitization these values
 * can carry CR/LF and forge fake log lines (CWE-117 / CodeQL "Log Injection"). They can also be
 * arbitrarily long and turn a single bad request into a megabyte of log output.
 *
 * <p>Use {@link #safe(String)} for any user-controlled value that ends up inside an SLF4J
 * placeholder. Trusted internal values (metric names, span attribute keys, our own enum
 * constants) do not need sanitization.
 *
 * <p>The sanitizer:
 *
 * <ul>
 *   <li>Replaces every ASCII control character (codepoint &lt; 0x20 and 0x7F) with {@code '_'}.
 *   <li>Truncates to {@link #MAX_LEN} characters and appends {@code "..."} when truncated.
 *   <li>Returns {@code "null"} (the literal string) for {@code null} input so format strings stay
 *       consistent.
 * </ul>
 */
public final class LogSanitizer {

    /** Defensive cap. URIs in the wild rarely exceed 2 KB; 256 is enough for log context. */
    public static final int MAX_LEN = 256;

    private LogSanitizer() {}

    public static String safe(@Nullable String value) {
        if (value == null) return "null";
        int len = Math.min(value.length(), MAX_LEN);
        StringBuilder out = new StringBuilder(len + 3);
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                out.append('_');
            } else {
                out.append(c);
            }
        }
        if (value.length() > MAX_LEN) {
            out.append("...");
        }
        return out.toString();
    }
}
