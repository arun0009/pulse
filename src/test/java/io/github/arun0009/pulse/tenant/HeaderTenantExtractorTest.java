package io.github.arun0009.pulse.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderTenantExtractorTest {

    private final HeaderTenantExtractor extractor = new HeaderTenantExtractor("Pulse-Tenant-Id");

    @Test
    void returnsHeaderValue() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Pulse-Tenant-Id", "acme");
        assertThat(extractor.extract(req)).contains("acme");
    }

    @Test
    void returnsEmptyWhenHeaderMissing() {
        assertThat(extractor.extract(new MockHttpServletRequest())).isEmpty();
    }

    @Test
    void returnsEmptyForBlankHeader() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Pulse-Tenant-Id", "   ");
        assertThat(extractor.extract(req)).isEmpty();
    }
}
