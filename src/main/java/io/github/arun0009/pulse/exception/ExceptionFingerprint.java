package io.github.arun0009.pulse.exception;

import org.jspecify.annotations.Nullable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Computes a stable, low-cardinality fingerprint for a {@link Throwable}.
 *
 * <p>The fingerprint is derived from the exception class plus the top {@code N} stack frames of
 * the root cause, hashed and truncated to a short hex string. The result is:
 *
 * <ul>
 *   <li><strong>Stable</strong> — the same bug produces the same fingerprint across hosts and
 *       deploys, so dashboards keyed on it cluster recurrences.
 *   <li><strong>Low cardinality</strong> — the truncated hash bounds the unique value space (10
 *       hex chars ≈ 1 trillion buckets, of which a real service exercises hundreds at most).
 *   <li><strong>Independent of message</strong> — exceptions whose only difference is a per-call
 *       id or timestamp in the message still cluster.
 * </ul>
 *
 * <p>This is the same idea Sentry / Rollbar / GlitchTip implement under the hood. Pulse exposes
 * it as a span attribute and a {@code ProblemDetail} property so any APM can group on it without
 * extra integration.
 */
public final class ExceptionFingerprint {

    private static final int FRAMES_TO_HASH = 5;
    private static final int FINGERPRINT_HEX_LENGTH = 10;

    private ExceptionFingerprint() {}

    public static String of(Throwable throwable) {
        Throwable root = rootCause(throwable);
        StringBuilder src = new StringBuilder(256);
        src.append(root.getClass().getName());

        StackTraceElement[] frames = root.getStackTrace();
        int limit = Math.min(FRAMES_TO_HASH, frames.length);
        for (int i = 0; i < limit; i++) {
            StackTraceElement f = frames[i];
            src.append('|')
                    .append(f.getClassName())
                    .append('#')
                    .append(f.getMethodName())
                    .append(':')
                    .append(f.getLineNumber());
        }
        return shortHash(src.toString());
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String shortHash(String input) {
        byte[] digest = sha256(input);
        StringBuilder hex = new StringBuilder(FINGERPRINT_HEX_LENGTH);
        for (int i = 0; i < FINGERPRINT_HEX_LENGTH / 2 && i < digest.length; i++) {
            hex.append(String.format("%02x", digest[i] & 0xff));
        }
        return hex.toString();
    }

    private static byte[] sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every JDK; this branch is unreachable.
            return fallbackHash(input);
        }
    }

    private static byte[] fallbackHash(String input) {
        return Arrays.copyOf(input.getBytes(java.nio.charset.StandardCharsets.UTF_8), FINGERPRINT_HEX_LENGTH / 2);
    }

    /** Convenience: returns null when the throwable is null. */
    public static @Nullable String ofNullable(@Nullable Throwable throwable) {
        return throwable == null ? null : of(throwable);
    }
}
