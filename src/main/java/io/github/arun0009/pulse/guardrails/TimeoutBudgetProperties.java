package io.github.arun0009.pulse.guardrails;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Timeout-budget propagation — extracts the configured inbound header (default
 * {@code Pulse-Timeout-Ms}, RFC 6648 — no {@code X-} prefix), places remaining-budget on
 * OTel baggage, and exposes it via {@code TimeoutBudget#current}. Downstream calls subtract
 * elapsed time so a 2s inbound budget with 800ms spent in business logic gives the next
 * downstream call exactly 1.2s — not the platform default. Inbound headers are clamped to
 * {@link #maximumBudget()} for edge safety.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.timeout-budget")
public record TimeoutBudgetProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("Pulse-Timeout-Ms") @NotBlank String inboundHeader,
        @DefaultValue("Pulse-Timeout-Ms") @NotBlank String outboundHeader,
        @DefaultValue("2s") Duration defaultBudget,
        @DefaultValue("30s") Duration maximumBudget,
        @DefaultValue("50ms") Duration safetyMargin,
        @DefaultValue("100ms") Duration minimumBudget,
        @DefaultValue @Valid PulseRequestMatcherProperties enabledWhen) {}
