package io.github.arun0009.pulse.dependencies;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Single source of truth for the {@code pulse.dependency.*} meters and their tag conventions.
 * Every transport-specific interceptor (RestTemplate, RestClient, WebClient, OkHttp) calls into
 * {@link #record(String, String, int, Throwable, long)} so the metric shape is identical
 * regardless of which client made the call.
 *
 * <p>Tag layout:
 *
 * <ul>
 *   <li>{@code dep} — logical dependency name from {@link DependencyResolver}
 *   <li>{@code method} — HTTP method (GET/POST/...)
 *   <li>{@code status} — response status code, or {@code "exception"} when the call threw
 *   <li>{@code outcome} — Spring-style coarse outcome ({@code SUCCESS} / {@code CLIENT_ERROR}
 *       / {@code SERVER_ERROR} / {@code UNKNOWN})
 * </ul>
 *
 * <p>The cardinality firewall protects all {@code pulse.dependency.*} meters by default — the
 * autoconfig adds them to {@code pulse.cardinality.meter-prefixes-to-protect}.
 */
public final class DependencyOutboundRecorder {

    private final MeterRegistry registry;
    private final DependencyResolver resolver;
    private final boolean enabled;

    public DependencyOutboundRecorder(
            MeterRegistry registry, DependencyResolver resolver, PulseProperties.Dependencies config) {
        this.registry = registry;
        this.resolver = resolver;
        this.enabled = config.enabled();
    }

    public boolean enabled() {
        return enabled;
    }

    public DependencyResolver resolver() {
        return resolver;
    }

    /**
     * Record a completed outbound call.
     *
     * @param logicalName the resolved {@code dep} tag value (already passed through {@link
     *     DependencyResolver}).
     * @param method HTTP method.
     * @param status HTTP status code, or any negative value when {@code throwable} is non-null
     *     (the {@code status} tag will be set to {@code "exception"}).
     * @param throwable the exception that ended the call, or {@code null} if it completed.
     * @param elapsedNanos wall-clock duration in nanoseconds.
     */
    public void record(
            String logicalName, String method, int status, @Nullable Throwable throwable, long elapsedNanos) {
        if (!enabled) return;
        String statusTag = throwable != null ? "exception" : Integer.toString(status);
        String outcome = outcome(status, throwable);
        Tags tags = Tags.of("dep", logicalName, "method", method, "status", statusTag, "outcome", outcome);
        Counter.builder("pulse.dependency.requests")
                .description("Outbound dependency call count, tagged by logical dependency name")
                .tags(tags)
                .register(registry)
                .increment();
        Timer.builder("pulse.dependency.latency")
                .description("Outbound dependency call latency, tagged by logical dependency name")
                .publishPercentileHistogram()
                .tags(Tags.of("dep", logicalName, "method", method, "outcome", outcome))
                .register(registry)
                .record(Duration.ofNanos(elapsedNanos));
        RequestFanout.record(logicalName);
    }

    private static String outcome(int status, @Nullable Throwable throwable) {
        if (throwable != null) return "UNKNOWN";
        if (status < 100) return "UNKNOWN";
        if (status < 400) return "SUCCESS";
        if (status < 500) return "CLIENT_ERROR";
        return "SERVER_ERROR";
    }
}
