package io.github.arun0009.pulse.dependencies;

import io.github.arun0009.pulse.core.PulseRequestMatcher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
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
    private final DependencyClassifier classifier;
    private final DependencyResolver resolver;
    private final boolean enabled;
    private final PulseRequestMatcher gate;

    public DependencyOutboundRecorder(
            MeterRegistry registry, DependencyResolver resolver, DependenciesProperties config) {
        this(registry, resolver, resolver, config, PulseRequestMatcher.ALWAYS);
    }

    public DependencyOutboundRecorder(
            MeterRegistry registry,
            DependencyClassifier classifier,
            DependencyResolver resolver,
            DependenciesProperties config,
            PulseRequestMatcher gate) {
        this.registry = registry;
        this.classifier = classifier;
        this.resolver = resolver;
        this.enabled = config.enabled();
        this.gate = gate;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the active {@link DependencyClassifier}. Used by every transport interceptor to
     * resolve the {@code dep} tag value before recording.
     *
     * <p>In Pulse 2.0+ this is a chain composite — callers should prefer
     * {@link #classify(URI)} / {@link #classifyHost(String)} below, which apply the
     * {@code default-name} fallback when every link returns {@code null}.
     */
    public DependencyClassifier classifier() {
        return classifier;
    }

    /**
     * Returns the built-in host-table resolver. Kept for backward compatibility; new code
     * should prefer {@link #classify(URI)} so a user-supplied {@link DependencyClassifier}
     * chain takes effect.
     */
    public DependencyResolver resolver() {
        return resolver;
    }

    /**
     * Classifies a URI against the chain and applies the {@code pulse.dependencies.default-name}
     * fallback if every link returned {@code null}. The result is guaranteed non-null and is
     * the value every transport interceptor uses for the {@code dep} tag.
     */
    public String classify(URI uri) {
        String dep = classifier.classify(uri);
        return dep != null ? dep : resolver.defaultName();
    }

    /** Host-string variant of {@link #classify(URI)} for transports without a full URI (Kafka, OkHttp). */
    public String classifyHost(String host) {
        String dep = classifier.classifyHost(host);
        return dep != null ? dep : resolver.defaultName();
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
        if (!shouldRecord()) return;
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

    private boolean shouldRecord() {
        if (gate == PulseRequestMatcher.ALWAYS) return true;
        HttpServletRequest request = currentRequest();
        // Outbound calls outside a request scope (scheduled jobs, Kafka consumers) have no
        // request to match — fail-open so the metric pipeline doesn't go quiet for legitimate
        // background traffic. Matching is opt-in.
        return request == null || gate.matches(request);
    }

    private static @Nullable HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) return sra.getRequest();
        return null;
    }

    private static String outcome(int status, @Nullable Throwable throwable) {
        if (throwable != null) return "UNKNOWN";
        if (status < 100) return "UNKNOWN";
        if (status < 400) return "SUCCESS";
        if (status < 500) return "CLIENT_ERROR";
        return "SERVER_ERROR";
    }
}
