package io.github.arun0009.pulse.guardrails;

import io.micrometer.core.instrument.MeterRegistry;
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
 * <p>When {@link TimeoutBudgetProperties#abortOnExhaustion()} is {@code true} and the remaining
 * budget is at or below {@code minimum-budget}, the interceptor throws
 * {@link TimeoutBudgetExhaustedException} and does not execute the call. The default is to stamp
 * the remaining budget (including {@code 0}) and the absolute {@link TimeoutBudget#DEADLINE_HEADER}
 * and still make the call. RestTemplate / RestClient have no per-request timeout API, so
 * {@code apply-client-timeout} does not apply here — use abort-on-exhaustion, or OkHttp /
 * WebClient / Apache HttpClient 5.
 *
 * <p>When the remaining budget is zero (the upstream caller's deadline has already passed) the
 * {@code pulse.timeout_budget.exhausted} counter is incremented, tagged with the {@code transport}
 * label supplied at construction time so dashboards can distinguish {@code transport=resttemplate}
 * from {@code transport=restclient}.
 */
public final class TimeoutBudgetOutboundInterceptor implements ClientHttpRequestInterceptor {

    private final TimeoutBudgetProperties config;
    private final TimeoutBudgetOutbound budgetHelper;
    private final String transport;

    public TimeoutBudgetOutboundInterceptor(TimeoutBudgetProperties config, MeterRegistry registry, String transport) {
        this.config = config;
        this.budgetHelper = new TimeoutBudgetOutbound(registry, config);
        this.transport = transport;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        budgetHelper.stampHeaders(config.outboundHeader(), transport, true, (name, value) -> {
            if (request.getHeaders().getFirst(name) == null) {
                request.getHeaders().add(name, value);
            }
        });
        return execution.execute(request, body);
    }
}
