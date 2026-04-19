package io.github.arun0009.pulse.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The thread-local-backed accumulator that powers N+1 detection. Each test asserts one
 * lifecycle invariant (begin/clear hygiene, verb counting, snapshot immutability) since a
 * regression here either silently misses N+1 events (if counts leak to zero) or double-counts
 * them across requests (if state leaks between scopes) — both bad failure modes.
 */
class DbObservationContextTest {

    @AfterEach
    void clearThreadLocal() {
        // Defensive: a failed test must never poison sibling tests in the same JVM.
        DbObservationContext.clear();
    }

    @Test
    void no_active_scope_means_record_statement_is_a_no_op() {
        DbObservationContext.recordStatement("SELECT 1");
        assertThat(DbObservationContext.isActive()).isFalse();
        assertThat(DbObservationContext.snapshot()).isNull();
    }

    @Test
    void begin_then_record_then_snapshot_returns_count_and_per_verb_breakdown() {
        DbObservationContext.begin("GET /orders/{id}");
        DbObservationContext.recordStatement("SELECT * FROM orders WHERE id = ?");
        DbObservationContext.recordStatement("select * from items where order_id = ?");
        DbObservationContext.recordStatement("UPDATE orders SET status = ? WHERE id = ?");

        DbObservationContext.Snapshot snap = DbObservationContext.snapshot();

        assertThat(snap).isNotNull();
        assertThat(snap.endpoint()).isEqualTo("GET /orders/{id}");
        assertThat(snap.statementCount()).isEqualTo(3);
        assertThat(snap.countsByVerb()).containsEntry("SELECT", 2).containsEntry("UPDATE", 1);
    }

    @Test
    void clear_removes_scope_so_next_record_is_no_op() {
        DbObservationContext.begin("GET /a");
        DbObservationContext.recordStatement("SELECT 1");
        DbObservationContext.clear();

        // Hot-path invariant: a pooled request thread that handles request A then request B
        // must not start B with A's counts. clear() in the filter's finally block is what
        // guarantees this, so a regression here would silently inflate B's count.
        DbObservationContext.recordStatement("SELECT 2");
        assertThat(DbObservationContext.snapshot()).isNull();
    }

    @Test
    void begin_replaces_existing_scope_atomically() {
        DbObservationContext.begin("GET /a");
        DbObservationContext.recordStatement("SELECT 1");
        DbObservationContext.begin("GET /b");

        DbObservationContext.Snapshot snap = DbObservationContext.snapshot();
        assertThat(snap).isNotNull();
        assertThat(snap.endpoint()).isEqualTo("GET /b");
        assertThat(snap.statementCount())
                .as("New scope must not inherit the previous scope's count")
                .isZero();
    }

    @Test
    void verb_extraction_normalizes_case_and_handles_leading_whitespace() {
        assertThat(DbObservationContext.extractVerb("  select 1")).isEqualTo("SELECT");
        assertThat(DbObservationContext.extractVerb("INSERT INTO foo VALUES (1)"))
                .isEqualTo("INSERT");
        assertThat(DbObservationContext.extractVerb("CREATE TABLE foo (id INT)"))
                .isEqualTo("OTHER");
        assertThat(DbObservationContext.extractVerb("")).isEqualTo("OTHER");
        assertThat(DbObservationContext.extractVerb("   ")).isEqualTo("OTHER");
    }

    @Test
    void snapshots_per_verb_map_is_a_defensive_copy() {
        DbObservationContext.begin("GET /a");
        DbObservationContext.recordStatement("SELECT 1");
        DbObservationContext.Snapshot snap = DbObservationContext.snapshot();

        // Snapshots leave the encoder boundary, so they must be immutable. If the test ever
        // throws UnsupportedOperationException on the next line, the encoder must be returning
        // a defensive copy — that's the contract.
        assertThat(snap).isNotNull();
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> snap.countsByVerb().put("SELECT", 99),
                "Snapshot must be immutable so callers cannot corrupt the encoder's view");
    }
}
