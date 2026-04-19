package io.github.arun0009.pulse.audit;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Standardized audit logger for security/compliance events.
 *
 * <p>Routes through the dedicated {@code AUDIT} logger so audit events can be sent to a separate
 * appender (e.g., a different Kafka topic or S3 bucket) without polluting application logs.
 *
 * <p>Two APIs are provided:
 *
 * <ul>
 *   <li><strong>Fluent builder</strong> ({@link #event(String)}) — preferred. Type-checked
 *       attribute names, structured context, and explicit {@code emit()} call:
 *       <pre>
 *       audit.event("order.created")
 *            .actor(currentUser.id())
 *            .resource("order:" + order.id())
 *            .outcome(Outcome.SUCCESS)
 *            .detail("amount", order.amount())
 *            .emit();
 *       </pre>
 *   <li><strong>Legacy positional</strong> ({@link #log(String, String, String, String)}) —
 *       deprecated, retained for backward compatibility. Will be removed in Pulse 2.0.
 * </ul>
 *
 * <p>All audit events emit at {@code INFO} level on the {@code AUDIT} logger with their
 * attributes mirrored to MDC for the duration of the call. Configure a dedicated appender for
 * the {@code AUDIT} logger in {@code log4j2-spring.xml} to route events to a separate sink.
 */
public class AuditLogger {

    /** Standardized outcome vocabulary for audit events. */
    public enum Outcome {
        SUCCESS,
        FAILURE,
        DENIED,
        ATTEMPTED;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** Begin building an audit event. The event is emitted when {@link Event#emit()} is called. */
    public Event event(String action) {
        return new Event(action);
    }

    /** @deprecated Use {@link #event(String)} fluent builder. Removed in Pulse 2.0. */
    @Deprecated(forRemoval = true)
    public void log(String action, String actor, String resource, String outcome) {
        log(action, actor, resource, outcome, null);
    }

    /** @deprecated Use {@link #event(String)} fluent builder. Removed in Pulse 2.0. */
    @Deprecated(forRemoval = true)
    public void log(String action, String actor, String resource, String outcome, @Nullable String detail) {
        Event evt = event(action).actor(actor).resource(resource).outcome(outcome);
        if (detail != null) evt.detail("detail", detail);
        evt.emit();
    }

    /**
     * Fluent audit-event builder.
     *
     * <p>Required: {@link #actor(String)}, {@link #resource(String)}, and either
     * {@link #outcome(String)} or {@link #outcome(Outcome)}. Missing required fields are recorded
     * as the literal string {@code "unknown"} so audit events still emit and gaps are visible
     * in dashboards rather than swallowed silently.
     */
    public static final class Event {

        private final String action;
        private @Nullable String actor;
        private @Nullable String resource;
        private @Nullable String outcome;
        private final Map<String, Object> details = new LinkedHashMap<>();

        private Event(String action) {
            this.action = action;
        }

        public Event actor(@Nullable String actor) {
            this.actor = actor;
            return this;
        }

        public Event resource(@Nullable String resource) {
            this.resource = resource;
            return this;
        }

        public Event outcome(@Nullable String outcome) {
            this.outcome = outcome;
            return this;
        }

        public Event outcome(Outcome outcome) {
            this.outcome = outcome.toString();
            return this;
        }

        public Event detail(String key, @Nullable Object value) {
            if (value != null) details.put(key, value);
            return this;
        }

        public void emit() {
            String resolvedActor = nonNull(actor);
            String resolvedResource = nonNull(resource);
            String resolvedOutcome = nonNull(outcome);

            MDC.put("audit.action", action);
            MDC.put("audit.actor", resolvedActor);
            MDC.put("audit.resource", resolvedResource);
            MDC.put("audit.outcome", resolvedOutcome);
            details.forEach((k, v) -> MDC.put("audit." + k, String.valueOf(v)));
            try {
                if (details.isEmpty()) {
                    AUDIT.info(
                            "AUDIT action={} actor={} resource={} outcome={}",
                            action,
                            resolvedActor,
                            resolvedResource,
                            resolvedOutcome);
                } else {
                    AUDIT.info(
                            "AUDIT action={} actor={} resource={} outcome={} details={}",
                            action,
                            resolvedActor,
                            resolvedResource,
                            resolvedOutcome,
                            details);
                }
            } finally {
                MDC.remove("audit.action");
                MDC.remove("audit.actor");
                MDC.remove("audit.resource");
                MDC.remove("audit.outcome");
                details.keySet().forEach(k -> MDC.remove("audit." + k));
            }
        }

        private static String nonNull(@Nullable String value) {
            return value == null ? "unknown" : value;
        }
    }
}
