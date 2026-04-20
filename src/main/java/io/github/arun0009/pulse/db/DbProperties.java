package io.github.arun0009.pulse.db;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Database observability — wires a Hibernate {@code StatementInspector} that counts every
 * prepared SQL statement against a per-request scope and emits the
 * {@code pulse.db.statements_per_request} + {@code pulse.db.n_plus_one.suspect} meters.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.db")
public record DbProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("50") @Min(1) int nPlusOneThreshold,
        @DefaultValue("500ms") Duration slowQueryThreshold,
        @DefaultValue @Valid PulseRequestMatcherProperties enabledWhen) {}
