package io.github.arun0009.pulse.dependencies;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Records {@code pulse.dependency.*} for every {@link org.springframework.web.client.RestTemplate
 * RestTemplate} or {@link org.springframework.web.client.RestClient RestClient} call. Implements
 * the same {@link ClientHttpRequestInterceptor} contract used by Pulse's MDC- and
 * timeout-budget-propagation interceptors so the wiring is consistent.
 *
 * <p>Even when the call throws, the recorder is invoked with the elapsed time and the
 * exception so the {@code pulse.dependency.requests{outcome=UNKNOWN}} counter ticks. This is
 * the only way an operator sees fail-fast circuit-breaker rejects in the dependency map.
 */
public final class DependencyClientHttpInterceptor implements ClientHttpRequestInterceptor {

    private final DependencyOutboundRecorder recorder;

    public DependencyClientHttpInterceptor(DependencyOutboundRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        long start = System.nanoTime();
        String dep = recorder.classify(request.getURI());
        String method = request.getMethod().name();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            recorder.record(dep, method, response.getStatusCode().value(), null, System.nanoTime() - start);
            return response;
        } catch (IOException | RuntimeException ex) {
            recorder.record(dep, method, -1, ex, System.nanoTime() - start);
            throw ex;
        }
    }
}
