package io.github.arun0009.pulse.logging;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;

/**
 * Log4j2 converter that masks the most common PII patterns before they hit disk. Conservative by
 * default — covers the cases that consistently produce post-hoc audit findings without producing
 * false positives that hide useful production diagnostics.
 *
 * <p>Patterns live on {@link PiiMasking} so the Logback encoder can redact without loading
 * Log4j2 types.
 *
 * <p>Configure in your log4j2 pattern as {@code %pii{%msg}}.
 */
@Plugin(name = "PiiMaskingConverter", category = "Converter")
@ConverterKeys({"pii"})
public class PiiMaskingConverter extends LogEventPatternConverter {

    /**
     * Log4j2 reflectively invokes {@code newInstance(String[])} for converters. Options are
     * reserved for future use (e.g. pattern variants); the array is intentionally read so static
     * analysis does not flag an unused parameter.
     */
    public static PiiMaskingConverter newInstance(final String[] options) {
        if (options != null && options.length > 0) {
            // Reserved — no behaviour yet.
        }
        return new PiiMaskingConverter();
    }

    private PiiMaskingConverter() {
        super("PiiMasking", "pii");
    }

    @Override
    public void format(LogEvent event, StringBuilder toAppendTo) {
        toAppendTo.append(mask(event.getMessage().getFormattedMessage()));
    }

    static String mask(String input) {
        return PiiMasking.mask(input);
    }
}
