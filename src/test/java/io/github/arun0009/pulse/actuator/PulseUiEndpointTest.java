package io.github.arun0009.pulse.actuator;

import io.github.arun0009.pulse.ext.PulseFeature;
import io.github.arun0009.pulse.slo.SloRuleGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PulseUiEndpointTest {

    @Test
    void html_is_self_contained_and_lists_every_subsystem_with_an_on_off_pill() {
        // The whole point of this endpoint is "open it in your browser, no other tools needed."
        // If it stops being self-contained (e.g. picks up an external CDN), or stops listing a
        // subsystem, this fails.
        PulseDiagnostics.AllProperties props = TestAllProperties.bindEmpty();
        PulseFeature demo = new PulseFeature() {
            @Override
            public String id() {
                return "test.demo";
            }

            @Override
            public String description() {
                return "demo user feature row";
            }
        };
        PulseDiagnostics diag = new PulseDiagnostics(
                props, "test-svc", "test-env", "0.0.1", 1.0, null, null, null, null, null, List.of(demo));
        PulseUiEndpoint endpoint = new PulseUiEndpoint(diag, new SloRuleGenerator(props.slo(), "test-svc"));

        String html = endpoint.html();

        assertThat(html).startsWith("<!doctype html>");
        assertThat(html).contains("Pulse — test-svc");
        // No external script/style/CDN dependencies — the page must work in an air-gapped cluster.
        assertThat(html).doesNotContain("<script").doesNotContain("<link rel=\"stylesheet\"");
        assertThat(html).contains("Subsystems").contains("User features").contains("test.demo");
        assertThat(html).contains("Runtime").contains("Effective config");
        assertThat(html).contains("./pulse/effective-config").contains("./pulse/runtime");
        assertThat(html)
                .contains("requestContext")
                .contains("traceGuard")
                .contains("cardinalityFirewall")
                .contains("timeoutBudget")
                .contains("wideEvents")
                .contains("kafka")
                .contains("slo");
        assertThat(html).contains("class=\"pill on\"").contains("class=\"pill off\"");
        assertThat(html).contains("apiVersion: monitoring.coreos.com/v1");
    }

    @Test
    void html_still_renders_when_user_feature_metadata_throws() {
        PulseDiagnostics.AllProperties props = TestAllProperties.bindEmpty();
        PulseFeature broken = new PulseFeature() {
            @Override
            public String id() {
                throw new IllegalStateException("boom");
            }
        };
        PulseDiagnostics diag = new PulseDiagnostics(
                props, "test-svc", "test-env", "0.0.1", 1.0, null, null, null, null, null, List.of(broken));
        PulseUiEndpoint endpoint = new PulseUiEndpoint(diag, null);

        String html = endpoint.html();

        assertThat(html).contains("User features");
        assertThat(html).contains("IllegalStateException");
        assertThat(html).contains(broken.getClass().getName());
    }
}
