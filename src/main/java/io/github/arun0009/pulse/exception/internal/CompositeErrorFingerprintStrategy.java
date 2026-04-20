package io.github.arun0009.pulse.exception.internal;

import io.github.arun0009.pulse.exception.ErrorFingerprintStrategy;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Walks an ordered list of {@link ErrorFingerprintStrategy} beans and returns the first
 * non-{@code null} fingerprint — the runtime expression of Pulse 2.0's
 * {@code ErrorFingerprintStrategy} chain-of-responsibility model.
 *
 * <p>The list is provided already-sorted by Spring's standard {@code @Order}/{@code @Priority}
 * resolution (lower value = earlier). The built-in {@link ErrorFingerprintStrategy#DEFAULT}
 * is registered with {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE} as the
 * terminal link, so user-supplied strategies (default order) get a chance first and any
 * exception that no link recognised falls back to the SHA-256 default.
 *
 * <p>Identity-deduplicates the input list so a strategy registered twice (e.g. as both a
 * {@code @Bean} and a participant qualifier) only fires once per call.
 */
@NullMarked
public final class CompositeErrorFingerprintStrategy implements ErrorFingerprintStrategy {

    private final List<ErrorFingerprintStrategy> chain;

    public CompositeErrorFingerprintStrategy(List<ErrorFingerprintStrategy> chain) {
        Objects.requireNonNull(chain, "chain");
        IdentityHashMap<ErrorFingerprintStrategy, Boolean> seen = new IdentityHashMap<>();
        this.chain = chain.stream()
                .filter(s -> seen.putIfAbsent(s, Boolean.TRUE) == null)
                .toList();
    }

    /** Visible-for-diagnostics: the resolved chain in invocation order. */
    public List<ErrorFingerprintStrategy> chain() {
        return chain;
    }

    @Override
    public @Nullable String fingerprint(Throwable throwable) {
        for (ErrorFingerprintStrategy link : chain) {
            String result = link.fingerprint(throwable);
            if (result != null) {
                return result;
            }
        }
        return null;
    }
}
