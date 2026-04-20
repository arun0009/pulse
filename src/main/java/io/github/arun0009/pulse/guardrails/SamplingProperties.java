package io.github.arun0009.pulse.guardrails;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Pulse-specific tuning of the OpenTelemetry trace sampler.
 *
 * <p>The <em>head sampling rate</em> itself is owned by Spring Boot's
 * {@code management.tracing.sampling.probability} property — Pulse intentionally does not
 * shadow it. Whatever {@link io.opentelemetry.sdk.trace.samplers.Sampler Sampler} bean Spring
 * Boot's tracing auto-configuration produces (typically
 * {@code ParentBased(TraceIdRatioBased(probability))}) is the base decision.
 *
 * <p>{@link #preferSamplingOnError()} is purely additive: when enabled (default), Pulse wraps
 * the discovered {@code Sampler} with {@link PreferErrorSampler}. Spans whose start attributes
 * already advertise an error (HTTP 5xx, {@code exception.type}, non-OK gRPC status, …) are
 * upgraded to {@code RECORD_AND_SAMPLE} regardless of the underlying sampler's decision.
 *
 * <p>This is an in-process best-effort heuristic — true tail sampling requires the
 * OpenTelemetry Collector — but it dramatically raises the recall on errors with negligible
 * volume cost.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.sampling")
public record SamplingProperties(@DefaultValue("true") boolean preferSamplingOnError) {}
