package io.github.arun0009.pulse.shutdown;

import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Lifecycle behavior on JVM shutdown. When enabled, Pulse blocks shutdown for up to
 * {@link #otelFlushTimeout()} so the OTel BatchSpanProcessor can flush queued spans —
 * without this, the last few hundred spans before a rolling deploy are lost.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.shutdown")
public record ShutdownProperties(
        @DefaultValue("true") boolean otelFlushEnabled,
        @DefaultValue("10s") Duration otelFlushTimeout,
        @DefaultValue @Valid Drain drain) {

    /**
     * Drain observability — counts inflight HTTP requests at the moment Spring's
     * {@code SmartLifecycle.stop()} fires, polls until they finish or the deadline
     * elapses, and emits structured before/after metrics.
     */
    public record Drain(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30s") Duration timeout) {}
}
