package io.github.arun0009.pulse.profiling;

import org.jspecify.annotations.Nullable;

/**
 * Detects whether the Pyroscope (Grafana Profiles) Java agent is loaded into the current JVM.
 * The detection is intentionally conservative — Pulse never tries to <em>start</em> a profiler,
 * only to observe one if it's there.
 *
 * <p>Detection checks, in order of cheapness:
 *
 * <ol>
 *   <li>System property {@code pyroscope.application.name} — set by the Pyroscope agent on
 *       startup. Most reliable signal short of probing the agent's classes.
 *   <li>System property {@code pyroscope.server.address} — set when the agent is configured to
 *       push profiles. Works even if {@code application.name} was not provided explicitly.
 *   <li>Class {@code io.pyroscope.javaagent.PyroscopeAgent} on the system classloader —
 *       loaded by the {@code -javaagent:pyroscope.jar} bootstrap. We use {@link Class#forName}
 *       with the boot loader rather than the current thread's classloader so the check works
 *       in containers where the application classloader does not see boot-loader classes.
 * </ol>
 *
 * <p>Each check is wrapped in defensive try/catch — a {@link SecurityException} from a
 * sandboxed runtime, a {@link LinkageError} from a half-loaded class, or a missing
 * {@code -Dpyroscope.*} property must never fail Spring context startup. Detection silently
 * returning "not present" is the right failure mode here.
 */
public final class PyroscopeDetector {

    private PyroscopeDetector() {}

    public record Detection(
            boolean present,
            @Nullable String applicationName,
            @Nullable String serverAddress) {
        public static final Detection ABSENT = new Detection(false, null, null);
    }

    public static Detection detect() {
        String app = readSystemProperty("pyroscope.application.name");
        String server = readSystemProperty("pyroscope.server.address");
        if (app != null || server != null || agentClassPresent()) {
            return new Detection(true, app, server);
        }
        return Detection.ABSENT;
    }

    private static @Nullable String readSystemProperty(String key) {
        try {
            String value = System.getProperty(key);
            return (value == null || value.isBlank()) ? null : value.trim();
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static boolean agentClassPresent() {
        try {
            Class.forName("io.pyroscope.javaagent.PyroscopeAgent", false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        } catch (SecurityException ignored) {
            return false;
        }
    }
}
