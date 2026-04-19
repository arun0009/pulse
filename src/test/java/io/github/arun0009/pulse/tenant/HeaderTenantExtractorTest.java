package io.github.arun0009.pulse.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderTenantExtractorTest {

    private final HeaderTenantExtractor extractor = new HeaderTenantExtractor("X-Tenant-ID");

    @Test
    void returnsHeaderValue() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Tenant-ID", "acme");
        assertThat(extractor.extract(req)).contains("acme");
    }

    @Test
    void returnsEmptyWhenHeaderMissing() {
        assertThat(extractor.extract(new MockHttpServletRequest())).isEmpty();
    }

    @Test
    void returnsEmptyForBlankHeader() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Tenant-ID", "   ");
        assertThat(extractor.extract(req)).isEmpty();
    }
}
