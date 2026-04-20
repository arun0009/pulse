package io.github.arun0009.pulse.dependencies;

import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/**
 * Builds the {@link ExchangeFilterFunction} that records {@code pulse.dependency.*} meters for
 * every {@link org.springframework.web.reactive.function.client.WebClient WebClient} call.
 * Lives in its own factory class so {@code WebClient}-typed signatures stay outside any class
 * that might be loaded when {@code spring-webflux} is absent from the application classpath.
 */
public final class DependencyExchangeFilter {

    private DependencyExchangeFilter() {}

    public static ExchangeFilterFunction filter(DependencyOutboundRecorder recorder) {
        return (request, next) -> {
            long start = System.nanoTime();
            String dep = recorder.classify(request.url());
            String method = request.method().name();
            return next.exchange(request)
                    .doOnSuccess(response -> {
                        if (response != null) {
                            recorder.record(
                                    dep, method, response.statusCode().value(), null, System.nanoTime() - start);
                        }
                    })
                    .doOnError(ex -> recorder.record(dep, method, -1, ex, System.nanoTime() - start));
        };
    }
}
