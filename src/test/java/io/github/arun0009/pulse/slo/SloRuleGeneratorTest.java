package io.github.arun0009.pulse.slo;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SloRuleGeneratorTest {

    @Test
    void empty_objectives_still_render_a_well_formed_document() {
        // The endpoint must always return parseable YAML so kubectl/yq pipelines don't break
        // on services that haven't declared any SLOs yet.
        SloRuleGenerator gen = new SloRuleGenerator(new SloProperties(true, List.of()), "checkout-service");

        String yaml = gen.render();

        assertThat(yaml).contains("apiVersion: monitoring.coreos.com/v1");
        assertThat(yaml).contains("kind: PrometheusRule");
        assertThat(yaml).contains("name: pulse-slo-checkout-service");
        assertThat(yaml).contains("No SLOs declared");
    }

    @Test
    void availability_objective_emits_recording_rules_and_two_burn_rate_alerts() {
        // Multi-window burn-rate is the SRE workbook recommendation: a fast page on 1h/14.4×
        // and a slow ticket on 6h/6×. Both must appear or the alerting story is incomplete.
        SloProperties.Objective obj = new SloProperties.Objective(
                "checkout-availability", "availability", 0.999, null, "http.server.requests", List.of());

        String yaml = new SloRuleGenerator(new SloProperties(true, List.of(obj)), "checkout").render();

        assertThat(yaml).contains("- record: pulse:slo:checkout-availability:good_ratio:1h");
        assertThat(yaml).contains("- record: pulse:slo:checkout-availability:good_ratio:6h");
        assertThat(yaml).contains("status!~\"5..\"");
        assertThat(yaml).contains("application=\"checkout\"");
        assertThat(yaml).contains("PulseSloBurn_checkout-availability_page");
        assertThat(yaml).contains("PulseSloBurn_checkout-availability_ticket");
        assertThat(yaml).contains("severity: page");
        assertThat(yaml).contains("severity: ticket");
    }

    @Test
    void latency_objective_uses_bucket_threshold_in_numerator() {
        SloProperties.Objective obj = new SloProperties.Objective(
                "checkout-latency",
                "latency",
                0.95,
                Duration.ofMillis(500),
                "http.server.requests",
                List.of("uri=\"/orders\""));

        String yaml = new SloRuleGenerator(new SloProperties(true, List.of(obj)), "checkout").render();

        assertThat(yaml).contains("http_server_requests_seconds_bucket{le=\"0.5\"");
        assertThat(yaml).contains("uri=\"/orders\"");
    }

    @Test
    void latency_objective_without_threshold_fails_loudly_at_render_time() {
        // Silent fallback to a default threshold would generate a perfectly valid alert that
        // means nothing — far worse than failing the actuator call. Better to surface the misconfig.
        SloProperties.Objective obj = new SloProperties.Objective(
                "checkout-latency", "latency", 0.95, null, "http.server.requests", List.of());

        SloRuleGenerator gen = new SloRuleGenerator(new SloProperties(true, List.of(obj)), "checkout");

        assertThatThrownBy(gen::render)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
    }

    @Test
    void burn_threshold_is_proportional_to_objective_target() {
        // 99.9% target → 0.1% budget → fast burn fires at 14.4 × 0.001 = 0.0144 (=1.44%).
        // 99% target  → 1% budget   → fast burn fires at 14.4 × 0.01  = 0.144  (=14.4%).
        // The burn-rate constant is fixed; the *threshold* moves with the target so the alert
        // remains correctly normalized regardless of how strict the SLO is.
        SloProperties.Objective tight =
                new SloProperties.Objective("tight", "availability", 0.999, null, "http.server.requests", List.of());
        SloProperties.Objective loose =
                new SloProperties.Objective("loose", "availability", 0.99, null, "http.server.requests", List.of());

        String yaml = new SloRuleGenerator(new SloProperties(true, List.of(tight, loose)), "svc").render();

        assertThat(yaml).contains("> 0.0144");
        assertThat(yaml).contains("> 0.1440");
    }
}
