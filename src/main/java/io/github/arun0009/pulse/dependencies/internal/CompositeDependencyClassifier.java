package io.github.arun0009.pulse.dependencies.internal;

import io.github.arun0009.pulse.dependencies.DependencyClassifier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Walks an ordered list of {@link DependencyClassifier} beans and returns the first
 * non-{@code null} classification — the runtime expression of Pulse 2.0's
 * {@code DependencyClassifier} chain-of-responsibility model.
 *
 * <p>The list is provided already-sorted by Spring's standard {@code @Order}/{@code @Priority}
 * resolution (lower value = earlier). The host-table {@link
 * io.github.arun0009.pulse.dependencies.DependencyResolver} is registered as the terminal
 * link with {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE} so user-supplied
 * classifiers (default order = lowest precedence too, but Spring gives them a stable
 * registration-order ahead of explicitly-LOWEST entries) get a chance first, and any input
 * that no link recognised falls back to {@code pulse.dependencies.default-name}.
 *
 * <p>This class is part of Pulse's internal wiring; consumers should not depend on it
 * directly. Inject {@link DependencyClassifier} instead — Spring resolves to the composite.
 */
@NullMarked
public final class CompositeDependencyClassifier implements DependencyClassifier {

    private final List<DependencyClassifier> chain;

    public CompositeDependencyClassifier(List<DependencyClassifier> chain) {
        Objects.requireNonNull(chain, "chain");
        // Dedupe by identity: the host-table DependencyResolver is exposed as both a typed
        // DependencyResolver bean and as the terminal DependencyClassifier link, so the same
        // instance shows up twice in the auto-discovered list. Identity preserves the
        // @Order-driven sequence Spring built.
        IdentityHashMap<DependencyClassifier, Boolean> seen = new IdentityHashMap<>();
        this.chain = chain.stream()
                .filter(c -> seen.putIfAbsent(c, Boolean.TRUE) == null)
                .toList();
    }

    /** Visible-for-diagnostics: the resolved chain in invocation order. */
    public List<DependencyClassifier> chain() {
        return chain;
    }

    @Override
    public @Nullable String classify(URI uri) {
        for (DependencyClassifier link : chain) {
            String result = link.classify(uri);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    @Override
    public @Nullable String classifyHost(String host) {
        for (DependencyClassifier link : chain) {
            String result = link.classifyHost(host);
            if (result != null) {
                return result;
            }
        }
        return null;
    }
}
