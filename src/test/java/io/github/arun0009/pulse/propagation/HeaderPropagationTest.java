package io.github.arun0009.pulse.propagation;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.github.arun0009.pulse.core.ContextKeys;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderPropagationTest {

    @Test
    void default_header_map_includes_request_correlation_user_tenant_and_idempotency() {
        // Idempotency-Key has to be in the default propagated set. Without it, retries through
        // a fan-out create silent duplicate side effects on the downstream — exactly the failure
        // mode pulse claims to prevent.
        PulseProperties.Context defaults = new PulseProperties.Context(
                true, "X-Request-ID", "X-Correlation-ID", "X-User-ID", "X-Tenant-ID", "Idempotency-Key", List.of());

        Map<String, String> map = HeaderPropagation.headerToMdcKey(defaults);

        assertThat(map)
                .containsEntry("X-Request-ID", ContextKeys.REQUEST_ID)
                .containsEntry("X-Correlation-ID", ContextKeys.CORRELATION_ID)
                .containsEntry("X-User-ID", ContextKeys.USER_ID)
                .containsEntry("X-Tenant-ID", ContextKeys.TENANT_ID)
                .containsEntry("Idempotency-Key", ContextKeys.IDEMPOTENCY_KEY);
    }

    @Test
    void custom_idempotency_header_name_is_honored_across_propagation_boundary() {
        // A consumer who already has an established header convention (e.g. Stripe-style
        // "X-Idempotency-Key") can override the name once and have RestTemplate, WebClient,
        // OkHttp, and Kafka all agree.
        PulseProperties.Context custom = new PulseProperties.Context(
                true, "X-Request-ID", "X-Correlation-ID", "X-User-ID", "X-Tenant-ID", "X-Idempotency-Key", List.of());

        Map<String, String> map = HeaderPropagation.headerToMdcKey(custom);

        assertThat(map)
                .doesNotContainKey("Idempotency-Key")
                .containsEntry("X-Idempotency-Key", ContextKeys.IDEMPOTENCY_KEY);
    }
}
