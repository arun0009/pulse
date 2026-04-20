package io.github.arun0009.pulse.exception.internal;

import io.github.arun0009.pulse.exception.ErrorFingerprintStrategy;
import io.github.arun0009.pulse.exception.ExceptionFingerprint;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Pulse 2.0 chain-of-responsibility behavior of {@link ErrorFingerprintStrategy}:
 * the composite walks every link in {@code @Order} sequence and the first non-null result wins,
 * with {@link ErrorFingerprintStrategy#DEFAULT} acting as the terminal SHA-256 fallback.
 */
class CompositeErrorFingerprintStrategyTest {

    @Nested
    class First_non_null_wins {

        @Test
        void specific_strategy_short_circuits_before_default() {
            ErrorFingerprintStrategy specific = ex -> ex instanceof IllegalStateException ? "ISE-event-id" : null;
            CompositeErrorFingerprintStrategy composite =
                    new CompositeErrorFingerprintStrategy(List.of(specific, ErrorFingerprintStrategy.DEFAULT));

            assertThat(composite.fingerprint(new IllegalStateException("boom")))
                    .as("specific match wins; the default SHA strategy is never consulted")
                    .isEqualTo("ISE-event-id");
        }

        @Test
        void specific_strategy_falls_through_to_default_when_unmatched() {
            ErrorFingerprintStrategy specific = ex -> ex instanceof IllegalStateException ? "ISE-event-id" : null;
            CompositeErrorFingerprintStrategy composite =
                    new CompositeErrorFingerprintStrategy(List.of(specific, ErrorFingerprintStrategy.DEFAULT));

            RuntimeException ex = new RuntimeException("not-ISE");
            assertThat(composite.fingerprint(ex))
                    .as("first link returns null → terminal SHA default produces the canonical hash")
                    .isEqualTo(ExceptionFingerprint.of(ex));
        }
    }

    @Nested
    class Terminal_default_guarantees_non_null {

        @Test
        void exception_with_no_matching_link_yields_sha_default() {
            CompositeErrorFingerprintStrategy composite =
                    new CompositeErrorFingerprintStrategy(List.of(ex -> null, ErrorFingerprintStrategy.DEFAULT));

            RuntimeException ex = new RuntimeException("anything");
            assertThat(composite.fingerprint(ex)).isEqualTo(ExceptionFingerprint.of(ex));
        }

        @Test
        void chain_returns_null_when_no_terminal_link_present() {
            // A misconfigured deployment (terminal removed) is not a panic — the composite
            // surfaces null and PulseExceptionHandler's local coalesce restores the SHA default.
            CompositeErrorFingerprintStrategy composite = new CompositeErrorFingerprintStrategy(List.of(ex -> null));

            assertThat(composite.fingerprint(new RuntimeException("boom"))).isNull();
        }
    }

    @Nested
    class Identity_dedup {

        @Test
        void same_strategy_registered_twice_only_fires_once() {
            CountingStrategy strategy = new CountingStrategy();
            CompositeErrorFingerprintStrategy composite =
                    new CompositeErrorFingerprintStrategy(List.of(strategy, strategy, strategy));

            composite.fingerprint(new RuntimeException("boom"));

            assertThat(strategy.invocations)
                    .as("identity dedup ensures a strategy registered under multiple bean names runs once")
                    .isEqualTo(1);
        }
    }

    private static final class CountingStrategy implements ErrorFingerprintStrategy {
        int invocations;

        @Override
        public String fingerprint(Throwable throwable) {
            invocations++;
            return null;
        }
    }
}
