package io.github.arun0009.pulse.audit;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Standardized audit logger for security/compliance events.
 *
 * <p>Routes through the dedicated {@code AUDIT} logger so audit events can be sent to a separate
 * appender (e.g., a different Kafka topic or S3 bucket) without polluting application logs.
 */
public class AuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    public void log(String action, String actor, String resource, String outcome) {
        log(action, actor, resource, outcome, null);
    }

    public void log(String action, String actor, String resource, String outcome, @Nullable String detail) {
        MDC.put("audit.action", action);
        MDC.put("audit.actor", actor);
        MDC.put("audit.resource", resource);
        MDC.put("audit.outcome", outcome);
        if (detail != null) MDC.put("audit.detail", detail);
        try {
            if (detail == null) {
                AUDIT.info("AUDIT action={} actor={} resource={} outcome={}", action, actor, resource, outcome);
            } else {
                AUDIT.info(
                        "AUDIT action={} actor={} resource={} outcome={} detail={}",
                        action,
                        actor,
                        resource,
                        outcome,
                        detail);
            }
        } finally {
            MDC.remove("audit.action");
            MDC.remove("audit.actor");
            MDC.remove("audit.resource");
            MDC.remove("audit.outcome");
            MDC.remove("audit.detail");
        }
    }
}
