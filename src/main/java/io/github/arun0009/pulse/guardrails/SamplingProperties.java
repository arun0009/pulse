package io.github.arun0009.pulse.guardrails;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Trace-sampler configuration.
 *
 * <p>The default is {@code ParentBased(TraceIdRatioBased(probability))}: 100% in dev, set
 * {@code probability} to {@code 0.1}–{@code 0.05} in production.
 *
 * <p>{@link #preferSamplingOnError()} composes a best-effort error-biased sampler on top:
 * when a span has its status set to {@code ERROR} or carries an exception attribute, Pulse
 * marks it sampled even if the parent's flag would have dropped it. This is an in-process
 * heuristic — true tail sampling requires the OpenTelemetry Collector — but it dramatically
 * raises the recall on errors with negligible volume cost.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.sampling")
public record SamplingProperties(
        @DefaultValue("1.0") @DecimalMin("0.0") @DecimalMax("1.0") double probability,

        @DefaultValue("true") boolean preferSamplingOnError) {}
