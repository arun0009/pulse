package io.github.arun0009.pulse.autoconfigure;

import io.github.arun0009.pulse.core.PulseRequestMatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a {@link PulseRequestMatcherProperties} block into a runtime {@link PulseRequestMatcher}.
 *
 * <p>Resolution rules:
 *
 * <ul>
 *   <li>{@code null} or {@link PulseRequestMatcherProperties#isEmpty() empty} block →
 *       {@link PulseRequestMatcher#ALWAYS}, i.e. the feature runs for every request (pre-1.1
 *       behaviour, full backward compatibility).
 *   <li>{@code bean: name} set → look up that bean from the {@link BeanFactory}; if found and
 *       it implements {@link PulseRequestMatcher}, use it directly. Declarative fields on the
 *       same block are ignored and a warning is logged.
 *   <li>Otherwise → produce a {@link DeclarativeMatcher} that AND-combines the populated
 *       header/path criteria.
 * </ul>
 *
 * <p>This factory runs once per feature at startup. The resulting matcher is held by the
 * feature's filter and consulted on every request, so the per-request cost is a single virtual
 * call into pre-compiled criteria.
 *
 * @since 1.1.0
 */
public final class PulseRequestMatcherFactory {

    private static final Logger log = LoggerFactory.getLogger(PulseRequestMatcherFactory.class);

    private final BeanFactory beanFactory;

    public PulseRequestMatcherFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * Builds a matcher for a feature's {@code enabled-when} block. {@code featureName} is used
     * only for log messages so misconfiguration warnings name the feature that owns the block.
     */
    public PulseRequestMatcher build(
            String featureName, @org.jspecify.annotations.Nullable PulseRequestMatcherProperties props) {
        if (props == null || props.isEmpty()) {
            return PulseRequestMatcher.ALWAYS;
        }

        if (props.bean() != null && !props.bean().isBlank()) {
            if (hasDeclarativeFields(props)) {
                log.warn(
                        "Pulse {}: enabled-when has both bean='{}' and declarative fields configured; the bean wins, declarative fields are ignored.",
                        featureName,
                        props.bean());
            }
            try {
                Object candidate = beanFactory.getBean(props.bean());
                if (candidate instanceof PulseRequestMatcher matcher) {
                    return matcher;
                }
                throw new IllegalStateException("Pulse "
                        + featureName
                        + ": enabled-when.bean='"
                        + props.bean()
                        + "' resolves to a "
                        + candidate.getClass().getName()
                        + " which does not implement "
                        + PulseRequestMatcher.class.getName());
            } catch (NoSuchBeanDefinitionException ex) {
                throw new IllegalStateException(
                        "Pulse "
                                + featureName
                                + ": enabled-when.bean='"
                                + props.bean()
                                + "' is not a registered bean. Define a "
                                + PulseRequestMatcher.class.getSimpleName()
                                + " bean with that name, or remove the bean reference.",
                        ex);
            }
        }

        return new DeclarativeMatcher(
                copyOrNull(props.headerEquals()),
                copyOrNull(props.headerNotEquals()),
                copyOrNull(props.headerPrefix()),
                props.pathMatches(),
                props.pathExcludes());
    }

    private static boolean hasDeclarativeFields(PulseRequestMatcherProperties p) {
        return (p.headerEquals() != null && !p.headerEquals().isEmpty())
                || (p.headerNotEquals() != null && !p.headerNotEquals().isEmpty())
                || (p.headerPrefix() != null && !p.headerPrefix().isEmpty())
                || (p.pathMatches() != null && !p.pathMatches().isEmpty())
                || (p.pathExcludes() != null && !p.pathExcludes().isEmpty());
    }

    private static @org.jspecify.annotations.Nullable Map<String, String> copyOrNull(
            @org.jspecify.annotations.Nullable Map<String, String> in) {
        return (in == null || in.isEmpty()) ? null : Map.copyOf(in);
    }

    /**
     * Pre-compiled, immutable matcher used when {@code enabled-when} carries declarative fields.
     * AND across populated fields, AND across map entries inside a field. {@link #pathExcludes}
     * short-circuits to {@code false}.
     */
    static final class DeclarativeMatcher implements PulseRequestMatcher {

        private final @org.jspecify.annotations.Nullable Map<String, String> headerEquals;
        private final @org.jspecify.annotations.Nullable Map<String, String> headerNotEquals;
        private final @org.jspecify.annotations.Nullable Map<String, String> headerPrefix;
        private final @org.jspecify.annotations.Nullable List<String> pathMatches;
        private final @org.jspecify.annotations.Nullable List<String> pathExcludes;

        DeclarativeMatcher(
                @org.jspecify.annotations.Nullable Map<String, String> headerEquals,
                @org.jspecify.annotations.Nullable Map<String, String> headerNotEquals,
                @org.jspecify.annotations.Nullable Map<String, String> headerPrefix,
                @org.jspecify.annotations.Nullable List<String> pathMatches,
                @org.jspecify.annotations.Nullable List<String> pathExcludes) {
            this.headerEquals = headerEquals;
            this.headerNotEquals = headerNotEquals;
            this.headerPrefix = headerPrefix;
            this.pathMatches = (pathMatches == null || pathMatches.isEmpty()) ? null : List.copyOf(pathMatches);
            this.pathExcludes = (pathExcludes == null || pathExcludes.isEmpty()) ? null : List.copyOf(pathExcludes);
        }

        @Override
        public boolean matches(HttpServletRequest request) {
            String path = request.getRequestURI();

            if (pathExcludes != null && path != null) {
                for (String exclude : pathExcludes) {
                    if (path.startsWith(exclude)) return false;
                }
            }

            if (pathMatches != null) {
                if (path == null) return false;
                boolean any = false;
                for (String prefix : pathMatches) {
                    if (path.startsWith(prefix)) {
                        any = true;
                        break;
                    }
                }
                if (!any) return false;
            }

            if (headerEquals != null) {
                for (Map.Entry<String, String> e : headerEquals.entrySet()) {
                    String actual = request.getHeader(e.getKey());
                    if (actual == null || !actual.equals(e.getValue())) return false;
                }
            }

            if (headerNotEquals != null) {
                for (Map.Entry<String, String> e : headerNotEquals.entrySet()) {
                    String actual = request.getHeader(e.getKey());
                    if (actual != null && actual.equals(e.getValue())) return false;
                }
            }

            if (headerPrefix != null) {
                for (Map.Entry<String, String> e : headerPrefix.entrySet()) {
                    String actual = request.getHeader(e.getKey());
                    if (actual == null || !actual.startsWith(e.getValue())) return false;
                }
            }

            return true;
        }

        Map<String, String> headerEqualsView() {
            return headerEquals == null ? Map.of() : new HashMap<>(headerEquals);
        }
    }
}
