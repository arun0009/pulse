package io.github.arun0009.pulse.guardrails;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * RestTemplate / RestClient interceptor that pushes the current request's remaining timeout budget
 * onto outbound calls as the configured header (default {@code Pulse-Timeout-Ms}). Downstream
 * Pulse-equipped services pick this up via {@link TimeoutBudgetFilter} and use it as their own
 * budget.
 *
 * <p>The interceptor does not change the underlying client's read/connect timeouts — that is highly
 * client-specific. Application code that wants a hard local cutoff can read {@link
 * TimeoutBudget#current()} directly and configure its client per-call.
 *
 * <p>When the remaining budget is zero (the upstream caller's deadline has already passed) the
 * {@code pulse.timeout_budget.exhausted} counter is incremented, tagged with the {@code transport}
 * label supplied at construction time so dashboards can distinguish {@code transport=resttemplate}
 * from {@code transport=restclient}.
 */
public final class TimeoutBudgetOutboundInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TimeoutBudgetOutboundInterceptor.class);

    private final TimeoutBudgetProperties config;
    private final TimeoutBudgetOutbound budgetHelper;
    private final String transport;

    public TimeoutBudgetOutboundInterceptor(TimeoutBudgetProperties config, MeterRegistry registry, String transport) {
        this.config = config;
        this.budgetHelper = new TimeoutBudgetOutbound(registry);
        this.transport = transport;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (request.getHeaders().getFirst(config.outboundHeader()) == null) {
            budgetHelper.resolveRemaining(transport).ifPresent(remaining -> {
                if (remaining.isZero()) {
                    log.debug(
                            "Pulse timeout-budget exhausted before outbound call to {}; "
                                    + "passing through with 0ms budget",
                            request.getURI());
                }
                request.getHeaders().add(config.outboundHeader(), Long.toString(remaining.toMillis()));
            });
        }
        return execution.execute(request, body);
    }
}
