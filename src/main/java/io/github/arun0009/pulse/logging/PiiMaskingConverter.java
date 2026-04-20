package io.github.arun0009.pulse.logging;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;

import java.util.regex.Pattern;

/**
 * Log4j2 converter that masks the most common PII patterns before they hit disk. Conservative by
 * default — covers the cases that consistently produce post-hoc audit findings without producing
 * false positives that hide useful production diagnostics.
 *
 * <p>Patterns masked: email addresses, US-style SSN ({@code 123-45-6789}), {@code Bearer ...} /
 * {@code token=...} headers, and JSON-like {@code "password": "..."} value pairs.
 *
 * <p>Configure in your log4j2 pattern as {@code %pii{%msg}}.
 */
@Plugin(name = "PiiMaskingConverter", category = "Converter")
@ConverterKeys({"pii"})
public class PiiMaskingConverter extends LogEventPatternConverter {

    private static final Pattern EMAIL = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,10}");
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern TOKEN = Pattern.compile("(?i)(bearer\\s+|token\\s*=\\s*)[a-zA-Z0-9\\-._~+/]+=*");
    private static final Pattern JSON_SECRETS =
            Pattern.compile("(?i)\"(password|secret|token|apikey|apiKey|api_key|key)\"\\s*:\\s*\"([^\"]+)\"");

    // Credit card numbers: 13–19 digit sequences that may contain spaces or dashes.
    // Matches Visa (4xxx), Mastercard (5xxx/2xxx), Amex (3xxx), Discover (6xxx), etc.
    private static final Pattern CREDIT_CARD = Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b");

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
        if (input == null || input.isEmpty()) return input;
        String s = EMAIL.matcher(input).replaceAll("[EMAIL]");
        s = SSN.matcher(s).replaceAll("[SSN]");
        s = CREDIT_CARD.matcher(s).replaceAll("[CREDIT_CARD]");
        s = TOKEN.matcher(s).replaceAll("$1[REDACTED]");
        s = JSON_SECRETS.matcher(s).replaceAll("\"$1\":\"[REDACTED]\"");
        return s;
    }
}
