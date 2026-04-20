package io.github.arun0009.pulse.test;

import io.github.arun0009.pulse.enforcement.PulseEnforcementMode;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.assertj.core.api.AbstractAssert;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Fluent assertions over Pulse's in-memory observability state. Captures the span / span-event
 * stream (via {@link InMemorySpanExporter}) and the Micrometer meter registry, so a single test
 * can assert on the trifecta a wide-event call produces (span event + counter increment + log,
 * though the log is asserted via standard {@code OutputCaptureExtension} if needed).
 *
 * <p>Wired automatically by {@link PulseTest}; inject as {@code @Autowired PulseTestHarness}.
 */
public class PulseTestHarness {

    private final InMemorySpanExporter spanExporter;
    private final MeterRegistry meterRegistry;
    private final @Nullable PulseEnforcementMode enforcementMode;

    public PulseTestHarness(
            InMemorySpanExporter spanExporter,
            MeterRegistry meterRegistry,
            @Nullable PulseEnforcementMode enforcementMode) {
        this.spanExporter = spanExporter;
        this.meterRegistry = meterRegistry;
        this.enforcementMode = enforcementMode;
    }

    /**
     * The active {@link PulseEnforcementMode} for the test context — handy in conjunction with
     * {@link PulseDryRun} when you want to assert the slice is genuinely in {@code DRY_RUN} or
     * to flip it for a single test method.
     *
     * @return the enforcement mode if Pulse's bean is in scope, otherwise empty.
     */
    public Optional<PulseEnforcementMode> enforcementMode() {
        return Optional.ofNullable(enforcementMode);
    }

    /** Drops captured spans and metric values — call from {@code @BeforeEach} for isolation. */
    public void reset() {
        spanExporter.reset();
        meterRegistry.clear();
    }

    public List<SpanData> spans() {
        return spanExporter.getFinishedSpanItems();
    }

    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    /** Starts a fluent assertion chain on a span event by name. */
    public PulseEventAssert assertEvent(String eventName) {
        EventData event = spans().stream()
                .flatMap(s -> s.getEvents().stream())
                .filter(e -> e.getName().equals(eventName))
                .findFirst()
                .orElse(null);
        return new PulseEventAssert(event, eventName, this);
    }

    /** Returns the first event with the given name, if any. */
    public Optional<EventData> findEvent(String eventName) {
        return spans().stream()
                .flatMap(s -> s.getEvents().stream())
                .filter(e -> e.getName().equals(eventName))
                .findFirst();
    }

    /** Returns the current counter value, or {@code 0.0} if no counter has been registered yet. */
    public double counterValue(String name, String... tagPairs) {
        var counter = meterRegistry.find(name).tags(tagPairs).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** Fluent assertion for a span event captured by Pulse's wide-event API. */
    public static class PulseEventAssert extends AbstractAssert<PulseEventAssert, EventData> {

        private final String eventName;
        private final PulseTestHarness harness;

        public PulseEventAssert(@Nullable EventData actual, String eventName, PulseTestHarness harness) {
            super(actual, PulseEventAssert.class);
            this.eventName = eventName;
            this.harness = harness;
        }

        /** Asserts that an event with the configured name was captured. */
        public PulseEventAssert exists() {
            isNotNull();
            return this;
        }

        /** Asserts the captured event carries an attribute with the given key + value. */
        public PulseEventAssert hasAttribute(String key, Object expected) {
            isNotNull();
            Object actualValue = readAttribute(key);
            if (actualValue == null) {
                failWithMessage(
                        "Expected event <%s> to carry attribute <%s>=<%s> but attribute was missing."
                                + " Present attributes: %s",
                        eventName, key, expected, actual.getAttributes());
            }
            String actualStr = String.valueOf(actualValue);
            String expectedStr = String.valueOf(expected);
            if (!actualStr.equals(expectedStr)) {
                failWithMessage(
                        "Expected event <%s> attribute <%s> to be <%s> but was <%s>",
                        eventName, key, expectedStr, actualStr);
            }
            return this;
        }

        /**
         * Asserts that a counter named {@code counterName} tagged {@code tagKey=tagValue} reached
         * the given value. Useful for verifying the metric side-effect of a wide-event call.
         */
        public PulseEventAssert incrementedCounter(
                String counterName, String tagKey, String tagValue, double byAmount) {
            isNotNull();
            double actualCount = harness.counterValue(counterName, tagKey, tagValue);
            if (actualCount != byAmount) {
                failWithMessage(
                        "Expected counter <%s{%s=%s}> to have value <%s> after event <%s> but was <%s>",
                        counterName, tagKey, tagValue, byAmount, eventName, actualCount);
            }
            return this;
        }

        @Nullable private Object readAttribute(String key) {
            return actual.getAttributes().asMap().entrySet().stream()
                    .filter(e -> e.getKey().getKey().equals(key))
                    .findFirst()
                    .map(java.util.Map.Entry::getValue)
                    .orElse(null);
        }
    }
}
