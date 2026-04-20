package io.github.arun0009.pulse.priority;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/**
 * Five-tier request criticality vocabulary, propagated end-to-end across the call chain so every
 * service participating in a request can make consistent load-shedding decisions.
 *
 * <p>The lower the {@link #ordinal()} the more important the request:
 *
 * <ul>
 *   <li>{@link #CRITICAL} — must complete; shed last. Examples: payment authorization, account
 *       deletion confirmation, regulator-driven flows.
 *   <li>{@link #HIGH} — paying-customer interactive traffic. Shed before CRITICAL but well
 *       before NORMAL.
 *   <li>{@link #NORMAL} — default. Most user-driven traffic.
 *   <li>{@link #LOW} — non-interactive but user-visible. E.g. background sync, recommendations
 *       refresh.
 *   <li>{@link #BACKGROUND} — analytics scrapers, replication, batch reconciliation. Shed first.
 * </ul>
 *
 * <p>Pulse normalizes any unknown header value to {@link #NORMAL} on read so application code
 * can rely on {@code current()} returning a defined enum constant. This matches the OTel
 * {@code request.priority} attribute convention used by the OpenTelemetry semantic-conventions
 * registry for request-shaping signals.
 */
public enum RequestPriority {
    CRITICAL("critical"),
    HIGH("high"),
    NORMAL("normal"),
    LOW("low"),
    BACKGROUND("background");

    private static final ThreadLocal<@Nullable RequestPriority> CURRENT = new ThreadLocal<>();

    private final String wireValue;

    RequestPriority(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The lowercase token transmitted on the wire and stamped on MDC / baggage. */
    public String wireValue() {
        return wireValue;
    }

    /** {@code true} when this priority is the most critical tier. */
    public boolean isCritical() {
        return this == CRITICAL;
    }

    /** Normalizes a header value or MDC string to a tier; returns {@code defaultValue} for nulls or unknowns. */
    public static RequestPriority parseOrDefault(@Nullable String value, RequestPriority defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        for (RequestPriority p : values()) {
            if (p.wireValue.equals(trimmed)) return p;
        }
        return defaultValue;
    }

    /** Sets the priority for the current thread. Pass {@code null} to clear. */
    public static void set(@Nullable RequestPriority value) {
        if (value == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(value);
        }
    }

    /** Clears the thread-local. */
    public static void clear() {
        CURRENT.remove();
    }

    /** The priority for this thread, if any has been set. */
    public static Optional<RequestPriority> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
