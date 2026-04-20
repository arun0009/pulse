package io.github.arun0009.pulse.propagation;

import io.github.arun0009.pulse.core.ContextKeys;
import io.github.arun0009.pulse.core.ContextProperties;
import io.github.arun0009.pulse.resilience.RetryProperties;
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
        ContextProperties defaults = new ContextProperties(
                true, "X-Request-ID", "X-Correlation-ID", "X-User-ID", "Pulse-Tenant-Id", "Idempotency-Key", List.of());

        Map<String, String> map = HeaderPropagation.headerToMdcKey(defaults);

        assertThat(map)
                .containsEntry("X-Request-ID", ContextKeys.REQUEST_ID)
                .containsEntry("X-Correlation-ID", ContextKeys.CORRELATION_ID)
                .containsEntry("X-User-ID", ContextKeys.USER_ID)
                .containsEntry("Pulse-Tenant-Id", ContextKeys.TENANT_ID)
                .containsEntry("Idempotency-Key", ContextKeys.IDEMPOTENCY_KEY);
    }

    @Test
    void custom_idempotency_header_name_is_honored_across_propagation_boundary() {
        // A consumer who already has an established header convention (e.g. Stripe-style
        // "X-Idempotency-Key") can override the name once and have RestTemplate, WebClient,
        // OkHttp, and Kafka all agree.
        ContextProperties custom = new ContextProperties(
                true,
                "X-Request-ID",
                "X-Correlation-ID",
                "X-User-ID",
                "Pulse-Tenant-Id",
                "X-Idempotency-Key",
                List.of());

        Map<String, String> map = HeaderPropagation.headerToMdcKey(custom);

        assertThat(map)
                .doesNotContainKey("Idempotency-Key")
                .containsEntry("X-Idempotency-Key", ContextKeys.IDEMPOTENCY_KEY);
    }

    @Test
    void retry_overload_adds_amplification_header_when_enabled() {
        ContextProperties context = new ContextProperties(
                true, "X-Request-ID", "X-Correlation-ID", "X-User-ID", "Pulse-Tenant-Id", "Idempotency-Key", List.of());
        RetryProperties retry = new RetryProperties(true, "Pulse-Retry-Depth", 3);

        Map<String, String> map = HeaderPropagation.headerToMdcKey(context, retry);

        assertThat(map).containsEntry("Pulse-Retry-Depth", ContextKeys.RETRY_DEPTH);
    }

    @Test
    void retry_overload_omits_amplification_header_when_disabled() {
        ContextProperties context = new ContextProperties(
                true, "X-Request-ID", "X-Correlation-ID", "X-User-ID", "Pulse-Tenant-Id", "Idempotency-Key", List.of());
        RetryProperties retry = new RetryProperties(false, "Pulse-Retry-Depth", 3);

        Map<String, String> map = HeaderPropagation.headerToMdcKey(context, retry);

        assertThat(map).doesNotContainKey("Pulse-Retry-Depth");
    }
}
