package io.github.arun0009.pulse.autoconfigure;

import io.github.arun0009.pulse.core.PulseRequestMatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The factory is the only thing that turns YAML into a runtime gate, so its semantics are
 * load-bearing for every feature that exposes {@code enabled-when}. These tests pin the
 * advertised contract: empty/null → ALWAYS, headers AND across the map, header-not-equals is
 * absence-tolerant, path excludes short-circuit path matches, and a misconfigured bean reference
 * fails fast at startup (not lazily on the first request).
 */
class PulseRequestMatcherFactoryTest {

    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    private final PulseRequestMatcherFactory factory = new PulseRequestMatcherFactory(beanFactory);

    @Test
    void null_or_empty_props_resolves_to_always_matching_singleton() {
        assertThat(factory.build("trace-guard", null)).isSameAs(PulseRequestMatcher.ALWAYS);
        assertThat(factory.build("trace-guard", emptyProps())).isSameAs(PulseRequestMatcher.ALWAYS);
    }

    @Test
    void header_equals_requires_every_listed_header_to_match() {
        PulseRequestMatcher m = factory.build(
                "trace-guard",
                new PulseRequestMatcherProperties(
                        Map.of("X-Region", "us-east-1", "X-Tier", "premium"),
                        Map.of(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        null));

        MockHttpServletRequest both = new MockHttpServletRequest("GET", "/x");
        both.addHeader("X-Region", "us-east-1");
        both.addHeader("X-Tier", "premium");
        assertThat(m.matches(both)).isTrue();

        MockHttpServletRequest oneMissing = new MockHttpServletRequest("GET", "/x");
        oneMissing.addHeader("X-Region", "us-east-1");
        assertThat(m.matches(oneMissing)).isFalse();
    }

    @Test
    void header_not_equals_returns_false_when_any_listed_header_matches_its_forbidden_value() {
        PulseRequestMatcher m = factory.build(
                "trace-guard",
                new PulseRequestMatcherProperties(
                        Map.of(), Map.of("client-id", "test-client-id"), Map.of(), List.of(), List.of(), null));

        MockHttpServletRequest synthetic = new MockHttpServletRequest("GET", "/x");
        synthetic.addHeader("client-id", "test-client-id");
        assertThat(m.matches(synthetic)).as("synthetic traffic must be skipped").isFalse();

        MockHttpServletRequest realUser = new MockHttpServletRequest("GET", "/x");
        realUser.addHeader("client-id", "real-user-7");
        assertThat(m.matches(realUser)).isTrue();

        MockHttpServletRequest noHeader = new MockHttpServletRequest("GET", "/x");
        assertThat(m.matches(noHeader))
                .as("absent header is fail-open: feature still applies")
                .isTrue();
    }

    @Test
    void header_prefix_matches_on_starts_with() {
        PulseRequestMatcher m = factory.build(
                "trace-guard",
                new PulseRequestMatcherProperties(
                        Map.of(), Map.of(), Map.of("user-agent", "PulseProbe/"), List.of(), List.of(), null));

        MockHttpServletRequest probe = new MockHttpServletRequest("GET", "/x");
        probe.addHeader("user-agent", "PulseProbe/2.1.0");
        assertThat(m.matches(probe)).isTrue();

        MockHttpServletRequest browser = new MockHttpServletRequest("GET", "/x");
        browser.addHeader("user-agent", "Mozilla/5.0");
        assertThat(m.matches(browser)).isFalse();
    }

    @Test
    void path_excludes_take_precedence_over_path_matches() {
        PulseRequestMatcher m = factory.build(
                "trace-guard",
                new PulseRequestMatcherProperties(
                        Map.of(), Map.of(), Map.of(), List.of("/api"), List.of("/api/internal"), null));

        assertThat(m.matches(new MockHttpServletRequest("GET", "/api/users"))).isTrue();
        assertThat(m.matches(new MockHttpServletRequest("GET", "/api/internal/sweep")))
                .isFalse();
        assertThat(m.matches(new MockHttpServletRequest("GET", "/static/style.css")))
                .as("path-matches requires at least one prefix to match")
                .isFalse();
    }

    @Test
    void all_fields_combine_with_and_semantics() {
        PulseRequestMatcher m = factory.build(
                "trace-guard",
                new PulseRequestMatcherProperties(
                        Map.of("X-Tier", "premium"),
                        Map.of("client-id", "test-client-id"),
                        Map.of(),
                        List.of("/api"),
                        List.of(),
                        null));

        MockHttpServletRequest pass = new MockHttpServletRequest("GET", "/api/orders");
        pass.addHeader("X-Tier", "premium");
        pass.addHeader("client-id", "real-user");
        assertThat(m.matches(pass)).isTrue();

        MockHttpServletRequest wrongTier = new MockHttpServletRequest("GET", "/api/orders");
        wrongTier.addHeader("X-Tier", "free");
        wrongTier.addHeader("client-id", "real-user");
        assertThat(m.matches(wrongTier)).isFalse();
    }

    @Test
    void bean_reference_delegates_to_user_supplied_matcher() {
        PulseRequestMatcher userMatcher = req -> "yes".equals(req.getHeader("X-Apply"));
        beanFactory.registerSingleton("myMatcher", userMatcher);

        PulseRequestMatcher resolved = factory.build(
                "trace-guard",
                new PulseRequestMatcherProperties(Map.of(), Map.of(), Map.of(), List.of(), List.of(), "myMatcher"));

        assertThat(resolved).isSameAs(userMatcher);

        MockHttpServletRequest yes = new MockHttpServletRequest("GET", "/x");
        yes.addHeader("X-Apply", "yes");
        assertThat(resolved.matches(yes)).isTrue();
    }

    @Test
    void missing_bean_reference_fails_fast_at_build_time() {
        assertThatThrownBy(() -> factory.build(
                        "trace-guard",
                        new PulseRequestMatcherProperties(
                                Map.of(), Map.of(), Map.of(), List.of(), List.of(), "doesNotExist")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trace-guard")
                .hasMessageContaining("doesNotExist");
    }

    @Test
    void wrong_typed_bean_fails_fast_at_build_time() {
        beanFactory.registerSingleton("notAMatcher", "I am a String");

        assertThatThrownBy(() -> factory.build(
                        "trace-guard",
                        new PulseRequestMatcherProperties(
                                Map.of(), Map.of(), Map.of(), List.of(), List.of(), "notAMatcher")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not implement");
    }

    private static PulseRequestMatcherProperties emptyProps() {
        return new PulseRequestMatcherProperties(Map.of(), Map.of(), Map.of(), List.of(), List.of(), null);
    }
}
