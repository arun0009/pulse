package io.github.arun0009.pulse.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SubdomainTenantExtractorTest {

    @Test
    void readsLeftmostLabelByDefault() {
        SubdomainTenantExtractor extractor = new SubdomainTenantExtractor(0);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Host", "acme.app.example.com");
        assertThat(extractor.extract(req)).contains("acme");
    }

    @Test
    void stripsPortFromHost() {
        SubdomainTenantExtractor extractor = new SubdomainTenantExtractor(0);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Host", "acme.app.example.com:8443");
        assertThat(extractor.extract(req)).contains("acme");
    }

    @Test
    void returnsEmptyWhenIndexOutOfRange() {
        SubdomainTenantExtractor extractor = new SubdomainTenantExtractor(5);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Host", "acme.app.example.com");
        assertThat(extractor.extract(req)).isEmpty();
    }

    @Test
    void returnsEmptyWhenHostMissing() {
        SubdomainTenantExtractor extractor = new SubdomainTenantExtractor(0);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServerName("");
        assertThat(extractor.extract(req)).isEmpty();
    }
}
