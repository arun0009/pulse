package io.github.arun0009.pulse.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the 1.1 {@link ErrorFingerprintStrategy} SPI: the default delegates to
 * {@link ExceptionFingerprint}, and a custom strategy fully replaces the hashing logic.
 */
class ErrorFingerprintStrategyTest {

    @Test
    void default_strategy_matches_exception_fingerprint() {
        IllegalStateException ex = new IllegalStateException("boom");

        assertThat(ErrorFingerprintStrategy.DEFAULT.fingerprint(ex)).isEqualTo(ExceptionFingerprint.of(ex));
    }

    @Test
    void custom_strategy_replaces_default_logic_entirely() {
        ErrorFingerprintStrategy strategy = throwable -> "static-event-id";

        assertThat(strategy.fingerprint(new RuntimeException("anything"))).isEqualTo("static-event-id");
        assertThat(strategy.fingerprint(new IllegalStateException("else")))
                .as("custom strategy is the source of truth — no fallback to the default")
                .isEqualTo("static-event-id");
    }
}
