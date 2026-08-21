package io.github.arun0009.pulse.guardrails;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Timeout-budget propagation — prefers inbound {@link TimeoutBudget#DEADLINE_HEADER} (absolute
 * epoch-millis), else the remaining-ms header (default {@code Pulse-Timeout-Ms}), else
 * {@link #defaultBudget()}. The deadline is stored on OTel baggage and exposed via
 * {@code TimeoutBudget#current}. An explicit remaining-ms of {@code 0} is an expired deadline,
 * not "no header": the next hop must not mint a fresh default. {@link #minimumBudget()} floors
 * only the implicit default, never an explicit inbound value. Outbound HTTP still executes by
 * default when remaining is exhausted; set {@link #abortOnExhaustion()} to skip those calls.
 * Set {@link #applyClientTimeout()} to bound OkHttp / WebClient / Apache HttpClient 5;
 * RestTemplate and RestClient cannot do this per request.
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
        @DefaultValue("false") boolean abortOnExhaustion,
        @DefaultValue("false") boolean applyClientTimeout,
        @DefaultValue @Valid PulseRequestMatcherProperties enabledWhen) {}
