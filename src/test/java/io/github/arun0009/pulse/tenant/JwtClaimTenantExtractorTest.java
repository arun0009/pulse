package io.github.arun0009.pulse.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class JwtClaimTenantExtractorTest {

    private final JwtClaimTenantExtractor extractor = new JwtClaimTenantExtractor("tenant_id");

    @Test
    void readsClaimFromBearerToken() {
        String jwt = jwt("{\"sub\":\"u1\",\"tenant_id\":\"acme\",\"iat\":1}");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + jwt);
        assertThat(extractor.extract(req)).contains("acme");
    }

    @Test
    void returnsEmptyWhenAuthorizationMissing() {
        assertThat(extractor.extract(new MockHttpServletRequest())).isEmpty();
    }

    @Test
    void returnsEmptyWhenAuthorizationIsNotBearer() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Basic abc==");
        assertThat(extractor.extract(req)).isEmpty();
    }

    @Test
    void returnsEmptyWhenClaimMissing() {
        String jwt = jwt("{\"sub\":\"u1\"}");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + jwt);
        assertThat(extractor.extract(req)).isEmpty();
    }

    @Test
    void returnsEmptyWhenPayloadIsNotBase64() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer header.@@@@.signature");
        assertThat(extractor.extract(req)).isEmpty();
    }

    @Test
    void returnsEmptyForMalformedJwt() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer abc");
        assertThat(extractor.extract(req)).isEmpty();
    }

    @Test
    void readsClaimWithSurroundingWhitespace() {
        String jwt = jwt("{\"tenant_id\" :  \"acme\" }");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + jwt);
        assertThat(extractor.extract(req)).contains("acme");
    }

    /** Build a 3-segment JWT with the given JSON payload (header + signature are dummies). */
    private static String jwt(String jsonPayload) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(jsonPayload.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }
}
