package io.github.arun0009.pulse.shutdown;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Observes graceful shutdown by polling {@link InflightRequestCounter} once Spring's lifecycle
 * begins to stop, recording the drain duration and the number of requests that were still
 * mid-flight when the deadline elapsed.
 *
 * <p>Emits:
 * <ul>
 *   <li>{@code pulse.shutdown.drain.duration} — Timer of how long the drain actually took.
 *   <li>{@code pulse.shutdown.dropped_total} — Counter of inflight requests still running when the
 *       deadline elapsed (i.e., were forcibly cut off when the JVM exited).
 * </ul>
 *
 * <p>Plus structured INFO log lines at start and end of drain so the rolling-deploy timeline is
 * obvious from logs alone. Cooperates with {@code server.shutdown=graceful}; the actual draining
 * is Spring Boot's job.
 *
 * <p>Phase is {@code Integer.MIN_VALUE / 2 + 1} so it stops <em>after</em>
 * {@link PulseOtelShutdownLifecycle} has flushed spans but before the web server lifecycle, so the
 * inflight count is still meaningful when we read it.
 */
public final class PulseDrainObservabilityLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PulseDrainObservabilityLifecycle.class);

    private final IntSupplier inflightSupplier;
    private final ShutdownProperties.Drain config;
    private final Timer drainTimer;
    private final Counter droppedCounter;
    private volatile boolean running;

    public PulseDrainObservabilityLifecycle(
            InflightRequestCounter inflightCounter, ShutdownProperties.Drain config, MeterRegistry registry) {
        this(inflightCounter::current, config, registry);
    }

    public PulseDrainObservabilityLifecycle(
            IntSupplier inflightSupplier, ShutdownProperties.Drain config, MeterRegistry registry) {
        this.inflightSupplier = inflightSupplier;
        this.config = config;
        this.drainTimer = Timer.builder("pulse.shutdown.drain.duration")
                .description("Wall-clock time spent waiting for inflight requests to drain at shutdown")
                .register(registry);
        this.droppedCounter = Counter.builder("pulse.shutdown.dropped")
                .description("Number of inflight requests still running when the drain deadline elapsed")
                .register(registry);
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        int initial = inflightSupplier.getAsInt();
        if (initial == 0) {
            log.info("Pulse: graceful shutdown — no inflight requests to drain");
            drainTimer.record(Duration.ZERO);
            return;
        }
        long deadlineNs = System.nanoTime() + config.timeout().toNanos();
        log.info(
                "Pulse: graceful shutdown — draining {} inflight request(s), timeout {}ms",
                initial,
                config.timeout().toMillis());
        long startNs = System.nanoTime();
        int remaining = initial;
        while ((remaining = inflightSupplier.getAsInt()) > 0 && System.nanoTime() < deadlineNs) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Re-read after loop exit — requests may have completed between the last poll
        // and now; using the stale loop variable would over-count dropped requests.
        remaining = inflightSupplier.getAsInt();
        long elapsedNs = System.nanoTime() - startNs;
        drainTimer.record(elapsedNs, TimeUnit.NANOSECONDS);
        if (remaining > 0) {
            droppedCounter.increment(remaining);
            log.warn(
                    "Pulse: graceful shutdown — {} request(s) still inflight after {}ms, will be cut off",
                    remaining,
                    config.timeout().toMillis());
        } else {
            log.info(
                    "Pulse: graceful shutdown — drained {} inflight request(s) in {}ms",
                    initial,
                    Duration.ofNanos(elapsedNs).toMillis());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE / 2 + 1;
    }
}
