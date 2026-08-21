package io.github.arun0009.pulse.logging;

import java.util.regex.Pattern;

/**
 * Logging-backend-agnostic PII redaction. Shared by the Log4j2 {@code %pii} converter and the
 * Logback JSON encoder so the Logback path does not load Log4j2 types (native-image AOT on a
 * Boot-default Logback app must not require {@code log4j-core}).
 */
public final class PiiMasking {

    private static final Pattern EMAIL = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,10}");
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern TOKEN = Pattern.compile("(?i)(bearer\\s+|token\\s*=\\s*)[a-zA-Z0-9\\-._~+/]+=*");
    private static final Pattern JSON_SECRETS =
            Pattern.compile("(?i)\"(password|secret|token|apikey|apiKey|api_key|key)\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CREDIT_CARD = Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b");

    private PiiMasking() {}

    public static String mask(String input) {
        if (input == null || input.isEmpty()) return input;
        String s = EMAIL.matcher(input).replaceAll("[EMAIL]");
        s = SSN.matcher(s).replaceAll("[SSN]");
        s = CREDIT_CARD.matcher(s).replaceAll("[CREDIT_CARD]");
        s = TOKEN.matcher(s).replaceAll("$1[REDACTED]");
        s = JSON_SECRETS.matcher(s).replaceAll("\"$1\":\"[REDACTED]\"");
        return s;
    }
}
