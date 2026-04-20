package io.github.arun0009.pulse.tenant;

import io.github.arun0009.pulse.core.ContextKeys;
import io.opentelemetry.api.baggage.Baggage;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextFilterTest {

    private static final TenantProperties CONFIG = new TenantProperties(
            true,
            new TenantProperties.Header(true, "Pulse-Tenant-Id"),
            new TenantProperties.Jwt(false, "tenant_id"),
            new TenantProperties.Subdomain(false, 0),
            100,
            "__overflow__",
            "unknown",
            List.of());

    @AfterEach
    void resetMdc() {
        MDC.clear();
        TenantContext.clear();
        System.clearProperty("pulse.tenant.id");
    }

    @Test
    void writesResolvedTenantToMdcAndContext() throws Exception {
        TenantContextFilter filter = filter(new HeaderTenantExtractor("Pulse-Tenant-Id"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Pulse-Tenant-Id", "acme");
        FilterChain chain = (request, response) -> {
            assertThat(MDC.get(ContextKeys.TENANT_ID)).isEqualTo("acme");
            assertThat(TenantContext.current()).contains("acme");
        };

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(MDC.get(ContextKeys.TENANT_ID)).isNull();
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void writesUnknownWhenNoExtractorMatches() throws Exception {
        TenantContextFilter filter = filter(new HeaderTenantExtractor("Pulse-Tenant-Id"));
        FilterChain chain = (request, response) -> {
            assertThat(MDC.get(ContextKeys.TENANT_ID)).isEqualTo("unknown");
            assertThat(TenantContext.current()).contains("unknown");
        };

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
    }

    @Test
    void systemPropertyOverridesAllExtractors() throws Exception {
        System.setProperty("pulse.tenant.id", "system-tenant");
        TenantContextFilter filter = filter(new HeaderTenantExtractor("Pulse-Tenant-Id"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Pulse-Tenant-Id", "header-tenant");
        FilterChain chain = (request, response) ->
                assertThat(MDC.get(ContextKeys.TENANT_ID)).isEqualTo("system-tenant");

        filter.doFilter(req, new MockHttpServletResponse(), chain);
    }

    @Test
    void firstExtractorWins() throws Exception {
        TenantExtractor jwtAlwaysAcme = req -> java.util.Optional.of("acme-jwt");
        TenantExtractor headerExtractor = new HeaderTenantExtractor("Pulse-Tenant-Id");
        // headerExtractor.getOrder() == 100, jwtAlwaysAcme has no Ordered so it sorts last in real
        // wiring; here we pass them ordered explicitly to assert "first non-empty wins" semantics.
        TenantContextFilter filter = new TenantContextFilter(List.of(headerExtractor, jwtAlwaysAcme), CONFIG);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Pulse-Tenant-Id", "header-acme");
        FilterChain chain = (request, response) ->
                assertThat(MDC.get(ContextKeys.TENANT_ID)).isEqualTo("header-acme");

        filter.doFilter(req, new MockHttpServletResponse(), chain);
    }

    @Test
    void mirrorsResolvedTenantOntoOtelBaggage() throws Exception {
        TenantContextFilter filter = filter(new HeaderTenantExtractor("Pulse-Tenant-Id"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Pulse-Tenant-Id", "acme");
        FilterChain chain =
                (request, response) -> assertThat(Baggage.current().getEntryValue(ContextKeys.TENANT_BAGGAGE_KEY))
                        .isEqualTo("acme");

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(Baggage.current().getEntryValue(ContextKeys.TENANT_BAGGAGE_KEY))
                .isNull();
    }

    @Test
    void restoresPreviousMdcAndContextOnExit() throws Exception {
        MDC.put(ContextKeys.TENANT_ID, "outer");
        TenantContext.set("outer");
        TenantContextFilter filter = filter(new HeaderTenantExtractor("Pulse-Tenant-Id"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Pulse-Tenant-Id", "inner");

        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {});

        assertThat(MDC.get(ContextKeys.TENANT_ID)).isEqualTo("outer");
        assertThat(TenantContext.current()).contains("outer");
    }

    private TenantContextFilter filter(TenantExtractor extractor) {
        return new TenantContextFilter(List.of(extractor), CONFIG);
    }
}
