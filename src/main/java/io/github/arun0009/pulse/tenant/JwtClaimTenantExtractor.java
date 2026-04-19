package io.github.arun0009.pulse.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Reads the tenant id from a single JWT claim on the {@code Authorization: Bearer ...} header.
 * <strong>Does not verify the JWT signature</strong> — that is Spring Security's job. By the
 * time a request reaches this extractor in production, Spring Security (or whatever the app
 * uses) will already have authenticated the token; reading a claim out of the same string is
 * just observability metadata.
 *
 * <p>Implements a tiny, dependency-free JWT payload reader: split on dots, base64-url-decode
 * the middle segment, scan the JSON for {@code "claim": "value"} or {@code "claim": "value"}.
 * No JSON library is pulled in for this. The reader handles only string-valued claims —
 * tenants are always opaque strings in well-modeled systems and number/object-valued claims
 * are not realistic identity carriers.
 *
 * <p>If anything looks malformed the extractor returns {@link Optional#empty()} silently. The
 * next extractor in the chain (subdomain or unknown) takes over. Pulse never throws or logs
 * a parse error from this code path — a half-rendered response from a caller is never the
 * observability layer's problem to surface.
 */
public final class JwtClaimTenantExtractor implements TenantExtractor, Ordered {

    /** Runs after the header extractor — the header is the more explicit, lower-cost signal. */
    public static final int ORDER = 200;

    private final String claim;
    private final Base64.Decoder urlDecoder = Base64.getUrlDecoder();

    public JwtClaimTenantExtractor(String claim) {
        this.claim = claim;
    }

    @Override
    public Optional<String> extract(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) return Optional.empty();
        String token = authorization.substring(7).trim();
        int firstDot = token.indexOf('.');
        if (firstDot < 0) return Optional.empty();
        int secondDot = token.indexOf('.', firstDot + 1);
        if (secondDot < 0) return Optional.empty();
        String payloadSegment = token.substring(firstDot + 1, secondDot);
        if (payloadSegment.isEmpty()) return Optional.empty();
        String payload;
        try {
            payload = new String(urlDecoder.decode(padded(payloadSegment)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        return findClaimValue(payload, claim);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /** Base64-url decoder requires padding when the encoded length is not a multiple of 4. */
    private static String padded(String s) {
        int mod = s.length() % 4;
        if (mod == 0) return s;
        StringBuilder sb = new StringBuilder(s.length() + (4 - mod));
        sb.append(s);
        for (int i = mod; i < 4; i++) sb.append('=');
        return sb.toString();
    }

    /**
     * Locates {@code "claimName": "stringValue"} in a JSON payload without parsing the whole
     * document. Tolerates whitespace, single + double quotes are both accepted on the value
     * side (some non-standard JWT producers emit single quotes).
     *
     * <p>Returns empty when the claim is not present or its value is not a string. Numeric or
     * object-typed claims are intentionally ignored — they are not realistic tenant carriers
     * and supporting them would force a JSON-library dependency.
     */
    private static Optional<String> findClaimValue(String payload, String claimName) {
        String needle = "\"" + claimName + "\"";
        int idx = payload.indexOf(needle);
        if (idx < 0) return Optional.empty();
        int colon = payload.indexOf(':', idx + needle.length());
        if (colon < 0) return Optional.empty();
        int cursor = colon + 1;
        while (cursor < payload.length() && Character.isWhitespace(payload.charAt(cursor))) cursor++;
        if (cursor >= payload.length()) return Optional.empty();
        char quote = payload.charAt(cursor);
        if (quote != '"' && quote != '\'') return Optional.empty();
        int valueStart = cursor + 1;
        int valueEnd = payload.indexOf(quote, valueStart);
        if (valueEnd < 0) return Optional.empty();
        String value = payload.substring(valueStart, valueEnd);
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
