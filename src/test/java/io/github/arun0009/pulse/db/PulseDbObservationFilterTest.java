package io.github.arun0009.pulse.db;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end behavior of the servlet filter that turns Hibernate statement counts into
 * Prometheus signals. Tests drive the filter with mock requests + a real
 * {@link SimpleMeterRegistry} so the metric names, tag keys, and threshold logic are all
 * asserted against the actual contract a downstream Grafana dashboard depends on.
 */
class PulseDbObservationFilterTest {

    private MeterRegistry registry;
    private PulseProperties.Db config;
    private PulseDbObservationFilter filter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        config = new PulseProperties.Db(true, 5, Duration.ofMillis(500));
        filter = new PulseDbObservationFilter(registry, config);
    }

    @AfterEach
    void clearThreadLocal() {
        DbObservationContext.clear();
    }

    @Test
    void requests_below_threshold_emit_distribution_summary_but_no_n_plus_one_counter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders/42");
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");
        MockHttpServletResponse res = new MockHttpServletResponse();

        FilterChain chain = (q, s) -> {
            DbObservationContext.recordStatement("SELECT * FROM orders WHERE id = ?");
            DbObservationContext.recordStatement("SELECT * FROM customers WHERE id = ?");
        };

        filter.doFilter(req, res, chain);

        assertThat(registry.find("pulse.db.statements_per_request")
                        .tags("endpoint", "GET /orders/{id}")
                        .summary()
                        .totalAmount())
                .isEqualTo(2.0);
        assertThat(registry.find("pulse.db.n_plus_one.suspect").counter())
                .as("Below-threshold requests must not pollute the suspect counter — it would "
                        + "drown the alert in noise")
                .isNull();
    }

    @Test
    void requests_at_or_above_threshold_increment_n_plus_one_counter_with_endpoint_tag() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders/7/items");
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}/items");
        MockHttpServletResponse res = new MockHttpServletResponse();

        FilterChain chain = (q, s) -> {
            for (int i = 0; i < 10; i++) {
                DbObservationContext.recordStatement("SELECT * FROM items WHERE id = ?");
            }
        };

        filter.doFilter(req, res, chain);

        assertThat(registry.counter("pulse.db.n_plus_one.suspect", Tags.of("endpoint", "GET /orders/{id}/items"))
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.find("pulse.db.statements_per_request")
                        .tags("endpoint", "GET /orders/{id}/items")
                        .summary()
                        .max())
                .isEqualTo(10.0);
    }

    @Test
    void thread_local_is_cleared_even_when_chain_throws() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (q, s) -> {
            DbObservationContext.recordStatement("SELECT 1");
            throw new RuntimeException("controller blew up");
        };

        try {
            filter.doFilter(req, res, chain);
        } catch (Exception ignored) {
            // expected
        }

        // Critical pooled-thread invariant: an exception in the controller must not leak the
        // thread-local into the next request that lands on this same Tomcat worker.
        assertThat(DbObservationContext.isActive()).isFalse();
    }

    @Test
    void zero_statement_requests_do_not_register_meters_to_avoid_label_explosion() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/health");
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (q, s) -> {
            /* no DB calls */
        };

        filter.doFilter(req, res, chain);

        // /actuator/health requests are common and overwhelmingly DB-free. Recording a 0-count
        // distribution every time would create endpoint=/actuator/health summary entries with
        // no useful information. Verify we skip the publish in that case.
        assertThat(registry.find("pulse.db.statements_per_request").summaries()).isEmpty();
    }

    @Test
    void endpoint_resolution_falls_back_to_id_sanitized_path_when_no_handler_pattern() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders/12345/items/abc");
        // BEST_MATCHING_PATTERN deliberately not set.
        String resolved = PulseDbObservationFilter.resolveEndpoint(req);
        // Numeric id collapses to {id}; non-numeric segment stays.
        assertThat(resolved).isEqualTo("GET /orders/{id}/items/abc");
    }

    @Test
    void endpoint_resolution_uses_uuid_template_when_segment_is_uuid_shaped() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/users/550e8400-e29b-41d4-a716-446655440000");
        String resolved = PulseDbObservationFilter.resolveEndpoint(req);
        assertThat(resolved).isEqualTo("GET /users/{id}");
    }
}
