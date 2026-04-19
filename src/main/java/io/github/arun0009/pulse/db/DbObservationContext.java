package io.github.arun0009.pulse.db;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-request, thread-local accumulator for SQL statement counts that backs Pulse's N+1
 * detection.
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>{@link PulseDbObservationFilter} calls {@link #begin(String)} on inbound request entry,
 *       seeding a fresh state on the request thread.
 *   <li>{@link PulseStatementInspector} calls {@link #recordStatement(String)} every time
 *       Hibernate prepares SQL — inspector callbacks run synchronously on the request thread.
 *   <li>{@link PulseDbObservationFilter} calls {@link #endAndPublish} after the controller
 *       returns, emitting the per-request histogram + N+1 suspect counter and clearing the
 *       thread-local.
 * </ol>
 *
 * <p>Why a thread-local rather than a request-scoped Spring bean: scope proxies on every
 * Hibernate prepare call would add a lookup-and-AOP cost on a hot path that fires for every
 * single SQL statement. The thread-local is a single inlined load. The trade-off is that
 * non-servlet flows (Kafka listeners, scheduled jobs) also need a corresponding entry/exit
 * pair if N+1 detection should apply there — see {@link #begin(String)} and
 * {@link #clear()} for that hook.
 *
 * <p>{@link InheritableThreadLocal} is deliberately <em>not</em> used: a Hibernate session is
 * almost always confined to a single thread, and inheriting the parent's counter into spawned
 * threads would conflate two requests' SQL counts.
 */
public final class DbObservationContext {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private DbObservationContext() {}

    /**
     * Starts a fresh request scope. {@code endpoint} should be a low-cardinality identifier
     * (e.g. matched URI template like {@code /orders/{id}}) so the resulting metric tag does
     * not explode into a per-path-id series.
     */
    public static void begin(String endpoint) {
        CURRENT.set(new State(endpoint));
    }

    /** Returns whether a scope is currently active on this thread. */
    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    /** Records one prepared SQL statement against the active scope. No-op if no scope is active. */
    public static void recordStatement(String sql) {
        State state = CURRENT.get();
        if (state == null) return;
        state.statementCount++;
        // Cheap heuristic for N+1: split on first space, take the verb. Records by verb so
        // dashboards can show "245 SELECTs in 1 request" without per-table cardinality.
        String verb = extractVerb(sql);
        state.byVerb.merge(verb, 1, Integer::sum);
    }

    /** Returns a snapshot of the current scope. {@code null} when no scope is active. */
    public static @Nullable Snapshot snapshot() {
        State state = CURRENT.get();
        if (state == null) return null;
        return new Snapshot(state.endpoint, state.statementCount, Map.copyOf(state.byVerb));
    }

    /** Clears the thread-local; safe to call from a {@code finally} regardless of begin state. */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Extracts the SQL verb (SELECT / INSERT / UPDATE / DELETE / CALL / OTHER) from a Hibernate
     * statement string. Cheap and allocation-free for the common case.
     */
    static String extractVerb(String sql) {
        if (sql == null || sql.isEmpty()) return "OTHER";
        int len = sql.length();
        int i = 0;
        while (i < len && Character.isWhitespace(sql.charAt(i))) i++;
        if (i >= len) return "OTHER";
        int start = i;
        while (i < len && !Character.isWhitespace(sql.charAt(i))) i++;
        String verb = sql.substring(start, i).toUpperCase(java.util.Locale.ROOT);
        return switch (verb) {
            case "SELECT", "INSERT", "UPDATE", "DELETE", "CALL", "MERGE" -> verb;
            default -> "OTHER";
        };
    }

    /** Mutable per-thread state; never escapes outside this class. */
    private static final class State {
        final String endpoint;
        int statementCount;
        final Map<String, Integer> byVerb = new HashMap<>(8);

        State(String endpoint) {
            this.endpoint = endpoint;
        }
    }

    /** Immutable snapshot suitable for actuator output / metric tagging. */
    public record Snapshot(String endpoint, int statementCount, Map<String, Integer> countsByVerb) {}
}
