package io.github.arun0009.pulse.bench;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.github.arun0009.pulse.events.SpanEvents;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmark for {@link SpanEvents#emit(String, Map)}. The wide-event API is intended to be
 * used on every business-relevant code path, so per-call overhead directly shapes adoption
 * willingness. Goal: well under 1µs in the no-op span, counter-disabled case.
 *
 * <pre>
 * mvn -Pbench package exec:java -Dexec.args="SpanEventsBenchmark"
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1)
@State(Scope.Benchmark)
public class SpanEventsBenchmark {

    private SpanEvents events;
    private SpanEvents eventsCounterOff;
    private Map<String, Object> attrs;

    @Setup
    public void setup() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        events = new SpanEvents(registry, new PulseProperties.WideEvents(true, true, false, "pulse.events", "event"));
        eventsCounterOff =
                new SpanEvents(registry, new PulseProperties.WideEvents(true, false, false, "pulse.events", "event"));
        attrs = Map.of(
                "orderId", "ord-12345",
                "amount", 4995L,
                "currency", "USD",
                "tier", "gold");
    }

    @Benchmark
    public void emit_no_attrs() {
        events.emit("bench.event");
    }

    @Benchmark
    public void emit_with_attrs() {
        events.emit("bench.event", attrs);
    }

    @Benchmark
    public void emit_with_attrs_counter_off() {
        eventsCounterOff.emit("bench.event", attrs);
    }
}
