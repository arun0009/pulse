package io.github.arun0009.pulse.bench;

import io.github.arun0009.pulse.guardrails.CardinalityFirewall;
import io.github.arun0009.pulse.guardrails.CardinalityProperties;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmark for the cardinality firewall's {@code map(Meter.Id)} hot path.
 *
 * <p>This is the function that runs on every meter registration. If it's slow, it regresses
 * application startup and on-demand meter creation latency. We bench three regimes:
 *
 * <ul>
 *   <li><b>cached</b>: the tag value has already been seen — fastest case.
 *   <li><b>new</b>: a never-before-seen value, but under the cap.
 *   <li><b>overflow</b>: the cap is exceeded — value is rewritten to OVERFLOW.
 * </ul>
 *
 * <pre>
 * mvn -Pbench package exec:java -Dexec.args="CardinalityFirewallBenchmark"
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1)
@State(Scope.Benchmark)
public class CardinalityFirewallBenchmark {

    @Param({"100", "1000"})
    public int maxTagValuesPerMeter;

    private CardinalityFirewall firewall;
    private Meter.Id cached;
    private Meter.Id overflowing;
    private SimpleMeterRegistry registry;

    @Setup
    public void setup() {
        registry = new SimpleMeterRegistry();
        firewall = new CardinalityFirewall(
                new CardinalityProperties(true, maxTagValuesPerMeter, "OVERFLOW", List.of(), List.of()),
                new io.github.arun0009.pulse.enforcement.PulseEnforcementMode(
                        io.github.arun0009.pulse.enforcement.PulseEnforcementMode.Mode.ENFORCING),
                () -> registry);

        Meter.Id baseId =
                new Meter.Id("bench.requests", Tags.of(Tag.of("uri", "/seed")), null, null, Meter.Type.COUNTER);
        for (int i = 0; i < maxTagValuesPerMeter; i++) {
            firewall.map(baseId.replaceTags(Tags.of(Tag.of("uri", "/path/" + i))));
        }
        cached = baseId.replaceTags(Tags.of(Tag.of("uri", "/path/0")));
        overflowing = baseId.replaceTags(Tags.of(Tag.of("uri", "/never-seen")));
    }

    @Benchmark
    public void cached_value(Blackhole bh) {
        bh.consume(firewall.map(cached));
    }

    @Benchmark
    public void overflow_value(Blackhole bh) {
        bh.consume(firewall.map(overflowing));
    }

    @Benchmark
    public void new_value_under_cap(Blackhole bh) {
        Meter.Id id = new Meter.Id(
                "bench.fresh." + ThreadLocalRandom.current().nextInt(8),
                Tags.of(Tag.of("uri", "/" + ThreadLocalRandom.current().nextInt(64))),
                null,
                null,
                Meter.Type.COUNTER);
        bh.consume(firewall.map(id));
    }
}
