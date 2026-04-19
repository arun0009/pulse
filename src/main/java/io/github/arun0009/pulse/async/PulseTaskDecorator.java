package io.github.arun0009.pulse.async;

import io.opentelemetry.context.Context;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Captures the calling thread's MDC and OTel {@link Context} (so traceId, spanId, baggage, and the
 * timeout-budget deadline all survive the hop) and restores them on the worker thread before the
 * wrapped {@link Runnable} runs.
 *
 * <p>Without this, every {@code @Async} dispatch loses trace context — the span dies at the
 * dispatch point and downstream logs become unjoinable.
 */
public final class PulseTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Context otelContext = Context.current();

        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            try (var ignored = otelContext.makeCurrent()) {
                if (mdc != null) MDC.setContextMap(mdc);
                else MDC.clear();
                runnable.run();
            } finally {
                if (previousMdc != null) MDC.setContextMap(previousMdc);
                else MDC.clear();
            }
        };
    }
}
