package io.github.arun0009.pulse.test;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.assertj.core.api.AbstractAssert;

import java.util.List;
import java.util.Optional;

/**
 * Fluent assertions over Pulse's in-memory observability state. Captures both the span/event stream
 * (via {@link InMemorySpanExporter}) and the Micrometer meter registry so tests can assert on the
 * trifecta a single wide-event call produces.
 */
public class PulseTestHarness {

    private final InMemorySpanExporter spanExporter;
    private final MeterRegistry meterRegistry;

    public PulseTestHarness(InMemorySpanExporter spanExporter, MeterRegistry meterRegistry) {
        this.spanExporter = spanExporter;
        this.meterRegistry = meterRegistry;
    }

    /** Drops captured spans and metric values — call between tests. */
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

    public PulseEventAssert assertEvent(String eventName) {
        EventData event = spans().stream()
                .flatMap(s -> s.getEvents().stream())
                .filter(e -> e.getName().equals(eventName))
                .findFirst()
                .orElse(null);
        return new PulseEventAssert(event, eventName, this);
    }

    public Optional<EventData> findEvent(String eventName) {
        return spans().stream()
                .flatMap(s -> s.getEvents().stream())
                .filter(e -> e.getName().equals(eventName))
                .findFirst();
    }

    public double counterValue(String name, String... tagPairs) {
        var counter = meterRegistry.find(name).tags(tagPairs).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** Fluent assertion for a wide-event captured on a span. */
    public static class PulseEventAssert extends AbstractAssert<PulseEventAssert, EventData> {

        private final String eventName;
        private final PulseTestHarness harness;

        public PulseEventAssert(EventData actual, String eventName, PulseTestHarness harness) {
            super(actual, PulseEventAssert.class);
            this.eventName = eventName;
            this.harness = harness;
        }

        public PulseEventAssert exists() {
            isNotNull();
            return this;
        }

        public PulseEventAssert hasAttribute(String key, Object expected) {
            isNotNull();
            Object actualValue = readAttribute(key);
            if (actualValue == null) {
                failWithMessage(
                        "Expected event <%s> to carry attribute <%s>=<%s> but attribute was"
                                + " missing. Present attributes: %s",
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

        public PulseEventAssert incrementedCounter(
                String counterName, String tagKey, String tagValue, double byAmount) {
            isNotNull();
            double actualCount = harness.counterValue(counterName, tagKey, tagValue);
            if (actualCount != byAmount) {
                failWithMessage(
                        "Expected counter <%s{%s=%s}> to have value <%s> after event <%s> but was" + " <%s>",
                        counterName, tagKey, tagValue, byAmount, eventName, actualCount);
            }
            return this;
        }

        private Object readAttribute(String key) {
            return actual.getAttributes().asMap().entrySet().stream()
                    .filter(e -> e.getKey().getKey().equals(key))
                    .findFirst()
                    .map(e -> e.getValue())
                    .orElse(null);
        }
    }
}
