package io.github.arun0009.pulse.guardrails;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.Duration;

/**
 * RestTemplate interceptor that pushes the current request's remaining timeout budget onto outbound
 * calls as the configured header (default {@code X-Timeout-Ms}). Downstream Pulse-equipped services
 * pick this up via {@link TimeoutBudgetFilter} and use it as their own budget.
 *
 * <p>The interceptor does not change the underlying client's read/connect timeouts — that is highly
 * client-specific. Application code that wants a hard local cutoff can read {@link
 * TimeoutBudget#current()} directly and configure its client per-call.
 */
public final class TimeoutBudgetOutboundInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TimeoutBudgetOutboundInterceptor.class);

    private final PulseProperties.TimeoutBudget config;
    private final Counter exhaustedCounter;

    public TimeoutBudgetOutboundInterceptor(PulseProperties.TimeoutBudget config, MeterRegistry registry) {
        this.config = config;
        this.exhaustedCounter = Counter.builder("pulse.timeout.budget.exhausted")
                .description("Outbound HTTP calls made with zero remaining budget — the upstream"
                        + " caller's deadline was already past when this hop fired.")
                .baseUnit("calls")
                .register(registry);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        TimeoutBudget.current().ifPresent(budget -> {
            Duration remaining = budget.remaining();
            if (remaining.isZero()) {
                exhaustedCounter.increment();
                log.debug(
                        "Pulse timeout-budget exhausted before outbound call to {};"
                                + " passing through with 0ms budget",
                        request.getURI());
            }
            if (request.getHeaders().getFirst(config.outboundHeader()) == null) {
                request.getHeaders().add(config.outboundHeader(), Long.toString(remaining.toMillis()));
            }
        });
        return execution.execute(request, body);
    }
}
