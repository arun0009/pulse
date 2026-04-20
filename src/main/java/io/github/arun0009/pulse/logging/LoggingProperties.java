package io.github.arun0009.pulse.logging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Logging integration — JSON layout, PII masking.
 *
 * <p>The bundled JSON layout always emits {@code traceId}, {@code spanId}, {@code app.version},
 * and {@code build.commit}; {@link #piiMaskingEnabled()} toggles automatic redaction of
 * emails, SSNs, credit cards, Bearer tokens, and JSON secret fields.
 */
@Validated
@ConfigurationProperties(prefix = "pulse.logging")
public record LoggingProperties(@DefaultValue("true") boolean piiMaskingEnabled) {}
