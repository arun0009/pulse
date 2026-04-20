package io.github.arun0009.pulse.dependencies;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

/**
 * OkHttp-side complement to {@link DependencyClientHttpInterceptor}. Records
 * {@code pulse.dependency.*} for every call routed through an {@link okhttp3.OkHttpClient}.
 *
 * <p>The MDC and timeout-budget propagation interceptors are still wired by
 * {@link io.github.arun0009.pulse.propagation.internal.OkHttpPropagationConfiguration}; this class purely
 * adds the dependency RED metrics layer and is registered as a separate interceptor on the same
 * builder.
 */
public final class DependencyOkHttpInterceptor implements Interceptor {

    private final DependencyOutboundRecorder recorder;

    public DependencyOkHttpInterceptor(DependencyOutboundRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        long start = System.nanoTime();
        String dep = recorder.classifyHost(request.url().host());
        String method = request.method();
        try {
            Response response = chain.proceed(request);
            recorder.record(dep, method, response.code(), null, System.nanoTime() - start);
            return response;
        } catch (IOException | RuntimeException ex) {
            recorder.record(dep, method, -1, ex, System.nanoTime() - start);
            throw ex;
        }
    }
}
