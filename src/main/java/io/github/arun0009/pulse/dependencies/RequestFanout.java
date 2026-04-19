package io.github.arun0009.pulse.dependencies;

import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Per-request thread-local that counts outbound calls and the distinct dependencies they touch.
 * Reset at the start of every inbound request by {@link RequestFanoutFilter}, snapshotted at the
 * end, and recorded as Micrometer distribution summaries.
 *
 * <p>Why thread-local: every outbound interceptor (RestTemplate, RestClient, WebClient, OkHttp,
 * Kafka producer) runs synchronously on the request thread when initiated from a servlet handler,
 * so a thread-local cleanly aggregates without coordinating across the interceptors. Reactive /
 * async paths fall back to "no fan-out tracking" rather than producing wrong numbers — the
 * distribution simply records 0 for those requests, which is a more honest signal than fabricated
 * cross-thread aggregation.
 */
public final class RequestFanout {

    private RequestFanout() {}

    private static final ThreadLocal<@Nullable State> STATE = new ThreadLocal<>();

    /** Begin tracking. Idempotent — calling twice keeps the existing state. */
    public static void begin() {
        if (STATE.get() == null) {
            STATE.set(new State());
        }
    }

    /** Increment the call count and add this dep to the distinct set. No-op when not tracking. */
    public static void record(String dependencyName) {
        State s = STATE.get();
        if (s == null) return;
        s.calls++;
        s.distinctDeps.add(dependencyName);
    }

    /** Snapshot the current request without clearing — used for late span attribution. */
    public static @Nullable Snapshot peek() {
        State s = STATE.get();
        if (s == null) return null;
        return new Snapshot(s.calls, s.distinctDeps.size());
    }

    /** Snapshot and clear. Returns null when no request was tracked. */
    public static @Nullable Snapshot end() {
        State s = STATE.get();
        if (s == null) return null;
        STATE.remove();
        return new Snapshot(s.calls, s.distinctDeps.size());
    }

    /** Number of outbound calls and distinct dependencies for the current inbound request. */
    public record Snapshot(int totalCalls, int distinctDependencies) {}

    private static final class State {
        int calls;
        final Set<String> distinctDeps = new HashSet<>();
    }
}
