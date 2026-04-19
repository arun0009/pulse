package io.github.arun0009.pulse.demo.edge;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Three deliberately leaky endpoints. Each one demonstrates one production failure mode that Pulse
 * mitigates by default:
 *
 * <ul>
 *   <li>{@code /trace/async} — trace context is lost across {@code @Async} unless something
 *       propagates MDC + OTel context for you.
 *   <li>{@code /trace/cardinality} — emitting a metric tagged with a per-user value will explode
 *       your time-series count without a firewall.
 *   <li>{@code /trace/timeout} — without budget propagation, a slow downstream consumes the
 *       caller's entire SLA before the caller even notices.
 * </ul>
 *
 * Compare the JSON log lines from {@code edge-with-pulse} vs {@code edge-without-pulse} for each
 * scenario. The difference is the entire pitch.
 */
@RestController
public class EdgeController {

    private static final Logger log = LoggerFactory.getLogger(EdgeController.class);

    private final RestClient downstream;
    private final MeterRegistry registry;

    public EdgeController(RestClient downstreamClient, MeterRegistry registry) {
        this.downstream = downstreamClient;
        this.registry = registry;
    }

    @GetMapping("/trace/async")
    public Map<String, String> async() {
        // Pulse's RequestContextFilter has already pulled X-Tenant-ID / X-Request-ID / X-User-ID
        // into MDC. The question is: does that MDC survive @Async? Spring Boot propagates traceId
        // natively, but custom MDC keys (which is where your *business* correlation lives) are
        // dropped unless something hydrates them on the worker thread.
        log.info(
                "[edge] entry — tenantId={} requestId={} userId={}",
                MDC.get("tenantId"),
                MDC.get("requestId"),
                MDC.get("userId"));
        scheduleBackground();
        return Map.of(
                "status",
                "scheduled — compare entry vs @Async log line: do tenantId/requestId/userId survive the worker thread?");
    }

    @Async
    void scheduleBackground() {
        log.info(
                "[edge] async work — tenantId={} requestId={} userId={}",
                MDC.get("tenantId"),
                MDC.get("requestId"),
                MDC.get("userId"));
    }

    @GetMapping("/trace/cardinality")
    public Map<String, Object> cardinality(@RequestParam(required = false) String id) {
        String userId = id != null ? id : UUID.randomUUID().toString();
        registry.counter("orders.placed", "userId", userId).increment();
        long distinctUsers = registry.find("orders.placed").counters().size();
        log.info("[edge] emitted orders.placed{{userId={}}} — distinct series so far: {}", userId, distinctUsers);
        return Map.of("emittedUserId", userId, "distinctSeries", distinctUsers);
    }

    @GetMapping("/trace/timeout")
    public Map<String, Object> timeout() {
        long start = System.currentTimeMillis();
        try {
            String body = downstream.get().uri("/slow").retrieve().body(String.class);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[edge] downstream returned in {}ms — body={}", elapsed, body);
            return Map.of("downstream", body, "elapsedMs", elapsed);
        } catch (RestClientException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[edge] downstream failed after {}ms — {}", elapsed, e.getMessage());
            return Map.of("error", e.getClass().getSimpleName(), "elapsedMs", elapsed);
        }
    }
}
