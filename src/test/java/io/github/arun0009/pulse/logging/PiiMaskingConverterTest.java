package io.github.arun0009.pulse.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conservative PII masking: covers the cases that show up in audit findings (email, SSN, bearer
 * tokens, JSON-serialized secrets) without producing the false-positive masking that would hide
 * useful production diagnostics.
 */
class PiiMaskingConverterTest {

    @Test
    void emails_are_masked() {
        assertThat(PiiMaskingConverter.mask("user signed in: alice@example.com from web"))
                .doesNotContain("alice@example.com")
                .contains("[EMAIL]");
    }

    @Test
    void ssn_pattern_is_masked() {
        assertThat(PiiMaskingConverter.mask("submitted ssn=123-45-6789"))
                .doesNotContain("123-45-6789")
                .contains("[SSN]");
    }

    @Test
    void bearer_tokens_are_masked() {
        String input = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature";
        assertThat(PiiMaskingConverter.mask(input))
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .contains("[REDACTED]");
    }

    @Test
    void json_serialized_secrets_are_masked() {
        String input = "{\"username\":\"alice\",\"password\":\"hunter2\",\"apiKey\":\"sk-abc123\"}";
        String out = PiiMaskingConverter.mask(input);
        assertThat(out)
                .doesNotContain("hunter2")
                .doesNotContain("sk-abc123")
                .contains("\"password\":\"[REDACTED]\"")
                .contains("\"apiKey\":\"[REDACTED]\"")
                .contains("\"username\":\"alice\"");
    }

    @Test
    void plain_messages_pass_through_unchanged() {
        String safe = "order placed for user u-12345 amount=49.99 currency=USD";
        assertThat(PiiMaskingConverter.mask(safe)).isEqualTo(safe);
    }

    @Test
    void null_and_empty_are_safe() {
        assertThat(PiiMaskingConverter.mask(null)).isNull();
        assertThat(PiiMaskingConverter.mask("")).isEmpty();
    }
}
