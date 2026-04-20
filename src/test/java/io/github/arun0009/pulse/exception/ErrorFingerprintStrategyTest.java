package io.github.arun0009.pulse.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@link ErrorFingerprintStrategy} SPI contract: the built-in {@code DEFAULT}
 * delegates to {@link ExceptionFingerprint}, and a custom strategy participates as one link
 * in the Pulse 2.0 chain (returning {@code null} delegates to the next strategy).
 *
 * <p>Chain composition itself is exercised by
 * {@link io.github.arun0009.pulse.exception.internal.CompositeErrorFingerprintStrategyTest}.
 */
class ErrorFingerprintStrategyTest {

    @Test
    void default_strategy_matches_exception_fingerprint() {
        IllegalStateException ex = new IllegalStateException("boom");

        assertThat(ErrorFingerprintStrategy.DEFAULT.fingerprint(ex)).isEqualTo(ExceptionFingerprint.of(ex));
    }

    @Test
    void custom_strategy_can_return_null_to_delegate_to_next_link() {
        ErrorFingerprintStrategy partial = throwable -> throwable instanceof IllegalArgumentException ? "iae-id" : null;

        assertThat(partial.fingerprint(new IllegalArgumentException("bad input")))
                .isEqualTo("iae-id");
        assertThat(partial.fingerprint(new RuntimeException("anything")))
                .as("returning null is the SPI's signal to delegate to the next chain link")
                .isNull();
    }

    @Test
    void custom_strategy_can_still_be_total() {
        // Pre-2.0 implementations that returned a non-null value for every input continue to
        // work — they simply always win the chain race when registered ahead of the terminal.
        ErrorFingerprintStrategy total = throwable -> "static-event-id";

        assertThat(total.fingerprint(new RuntimeException("anything"))).isEqualTo("static-event-id");
        assertThat(total.fingerprint(new IllegalStateException("else"))).isEqualTo("static-event-id");
    }
}
