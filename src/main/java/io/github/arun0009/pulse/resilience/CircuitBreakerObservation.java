package io.github.arun0009.pulse.resilience;

import io.github.arun0009.pulse.tracing.internal.PulseSpans;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerEvent;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnErrorEvent;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnIgnoredErrorEvent;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Observes a Resilience4j {@link CircuitBreakerRegistry} and turns its events into Pulse's
 * standard signals: structured log lines, Micrometer counters/gauges, and active-span events.
 *
 * <p>Three observability surfaces are wired:
 *
 * <ul>
 *   <li>State transitions — {@code pulse.resilience.circuit_breaker.state_transitions{name, from,
 *       to}} counter + a span event named {@code pulse.resilience.circuit_breaker.state_transition}
 *       with the same attributes. This is the headline signal: a transition from CLOSED to OPEN
 *       is the canonical "we just shed load" event in any production system.
 *   <li>Live state — {@code pulse.resilience.circuit_breaker.state{name}} gauge that maps the
 *       current state to a numeric value (0=CLOSED, 1=DISABLED, 2=METRICS_ONLY, 3=HALF_OPEN,
 *       4=OPEN, 5=FORCED_OPEN). Lets dashboards show "currently OPEN" without having to derive
 *       it from the transitions counter.
 *   <li>Error counter — {@code pulse.resilience.circuit_breaker.errors{name}} for non-ignored
 *       errors recorded by the circuit breaker. Counter; the Prometheus exposition adds
 *       {@code _total}.
 * </ul>
 *
 * <p>The observer attaches via {@link CircuitBreakerRegistry#getEventPublisher()} which fires
 * for both pre-existing and lazily-instantiated breakers. {@link SmartInitializingSingleton}
 * runs after all singletons are wired so any user-declared breaker is also observed.
 *
 * <p>Cardinality budget: every metric is tagged by {@code name} only — bounded by the number of
 * breakers a service declares (single-digit in practice). The transition counter additionally
 * carries {@code from} / {@code to}; with six possible states, that's a ceiling of 36 entries
 * per breaker — well within the firewall's per-meter limit.
 */
public final class CircuitBreakerObservation implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger("pulse.resilience.circuit-breaker");

    private final CircuitBreakerRegistry registry;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    /** Tracks breakers we've already wired so we don't double-subscribe on lazy creation events. */
    private final ConcurrentMap<String, Boolean> wired = new ConcurrentHashMap<>();

    public CircuitBreakerObservation(CircuitBreakerRegistry registry, MeterRegistry meterRegistry) {
        this(registry, meterRegistry, Tracer.NOOP);
    }

    public CircuitBreakerObservation(CircuitBreakerRegistry registry, MeterRegistry meterRegistry, Tracer tracer) {
        this.registry = registry;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // Wire any breakers that already exist at startup.
        registry.getAllCircuitBreakers().forEach(this::attach);
        // And wire any future lazily-created breakers.
        registry.getEventPublisher().onEntryAdded(event -> attach(event.getAddedEntry()));
    }

    private void attach(CircuitBreaker breaker) {
        if (wired.putIfAbsent(breaker.getName(), Boolean.TRUE) != null) return;

        registerStateGauge(breaker);

        breaker.getEventPublisher()
                .onStateTransition(this::onStateTransition)
                .onError(this::onError)
                .onIgnoredError(this::onIgnoredError);
    }

    private void registerStateGauge(CircuitBreaker breaker) {
        Gauge.builder("pulse.resilience.circuit_breaker.state", breaker, b -> b.getState()
                        .getOrder())
                .description(
                        "Current circuit breaker state (0=CLOSED, 3=HALF_OPEN, 4=OPEN; see CircuitBreaker.State.getOrder)")
                .tag("name", breaker.getName())
                .register(meterRegistry);
    }

    private void onStateTransition(CircuitBreakerOnStateTransitionEvent event) {
        String name = event.getCircuitBreakerName();
        String from = event.getStateTransition().getFromState().name();
        String to = event.getStateTransition().getToState().name();

        meterRegistry
                .counter(
                        "pulse.resilience.circuit_breaker.state_transitions",
                        Tags.of("name", name, "from", from, "to", to))
                .increment();

        Span span = PulseSpans.recordable(tracer);
        if (span != null) {
            span.event("pulse.resilience.circuit_breaker.state_transition");
            span.tag("pulse.resilience.circuit_breaker.name", name);
            span.tag("pulse.resilience.circuit_breaker.from", from);
            span.tag("pulse.resilience.circuit_breaker.to", to);
        }

        log.warn("circuit breaker {} transitioned {} -> {}", name, from, to);
    }

    private void onError(CircuitBreakerOnErrorEvent event) {
        meterRegistry
                .counter("pulse.resilience.circuit_breaker.errors", Tags.of("name", event.getCircuitBreakerName()))
                .increment();
    }

    private void onIgnoredError(CircuitBreakerOnIgnoredErrorEvent event) {
        meterRegistry
                .counter(
                        "pulse.resilience.circuit_breaker.ignored_errors",
                        Tags.of("name", event.getCircuitBreakerName()))
                .increment();
    }

    /** Test seam: number of breakers currently wired. */
    int wiredCount() {
        return wired.size();
    }

    /** Test seam: drain any pending events for assertion. */
    static String describe(CircuitBreakerEvent event) {
        return event.getEventType().name();
    }
}
