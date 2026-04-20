package io.github.arun0009.pulse.slo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * SLO-as-code. Declare service-level objectives in {@code application.yml}; Pulse renders
 * them at {@code /actuator/pulse/slo} as a Prometheus {@code PrometheusRule} document
 * (recording rules + multi-window burn-rate alerts), ready to {@code kubectl apply -f -}.
 *
 * <pre>
 * pulse:
 *   slo:
 *     objectives:
 *       - name: orders-availability
 *         sli: availability
 *         target: 0.999
 *       - name: orders-latency
 *         sli: latency
 *         target: 0.95
 *         threshold: 500ms
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "pulse.slo")
public record SloProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue({}) List<@Valid Objective> objectives) {

    public record Objective(
            @NotBlank String name,
            @DefaultValue("availability") @NotBlank String sli,

            @DefaultValue("0.999") @DecimalMin("0.0") @DecimalMax("1.0") double target,

            @Nullable Duration threshold,
            @DefaultValue("http.server.requests") @NotBlank String meter,
            @DefaultValue({}) List<String> filters) {}
}
