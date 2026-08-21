package io.github.arun0009.pulse.propagation.internal;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import io.github.arun0009.pulse.guardrails.TimeoutBudget;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetOutbound;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetProperties;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PulseOkHttpInterceptorTimeoutTest {

    @Test
    void apply_client_timeout_caps_connect_read_and_write() throws IOException {
        TimeoutBudgetProperties config = props(true);
        TimeoutBudgetOutbound helper = new TimeoutBudgetOutbound(null, config);
        OkHttpPropagationConfiguration.PulseOkHttpInterceptor interceptor =
                new OkHttpPropagationConfiguration.PulseOkHttpInterceptor(Map.of(), "Pulse-Timeout-Ms", true, helper);

        TimeoutBudget budget = TimeoutBudget.withRemaining(Duration.ofMillis(800));
        RecordingChain chain = new RecordingChain(
                new Request.Builder().url("http://example.test/stock").build());

        try (Scope ignored = openBudget(budget)) {
            interceptor.intercept(chain);
        }

        assertThat(chain.connectTimeoutMs).isBetween(500, 800);
        assertThat(chain.readTimeoutMs).isEqualTo(chain.connectTimeoutMs);
        assertThat(chain.writeTimeoutMs).isEqualTo(chain.connectTimeoutMs);
        assertThat(chain.proceeded.header("Pulse-Timeout-Ms")).isNotBlank();
    }

    @Test
    void default_does_not_rewrite_okhttp_timeouts() throws IOException {
        TimeoutBudgetProperties config = props(false);
        TimeoutBudgetOutbound helper = new TimeoutBudgetOutbound(null, config);
        OkHttpPropagationConfiguration.PulseOkHttpInterceptor interceptor =
                new OkHttpPropagationConfiguration.PulseOkHttpInterceptor(Map.of(), "Pulse-Timeout-Ms", true, helper);

        TimeoutBudget budget = TimeoutBudget.withRemaining(Duration.ofMillis(800));
        RecordingChain chain = new RecordingChain(
                new Request.Builder().url("http://example.test/stock").build());

        try (Scope ignored = openBudget(budget)) {
            interceptor.intercept(chain);
        }

        assertThat(chain.connectTimeoutMs).isEqualTo(10_000);
        assertThat(chain.readTimeoutMs).isEqualTo(10_000);
        assertThat(chain.writeTimeoutMs).isEqualTo(10_000);
    }

    private static TimeoutBudgetProperties props(boolean applyClientTimeout) {
        return new TimeoutBudgetProperties(
                true,
                "Pulse-Timeout-Ms",
                "Pulse-Timeout-Ms",
                Duration.ofSeconds(2),
                Duration.ofSeconds(30),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                false,
                applyClientTimeout,
                PulseRequestMatcherProperties.empty());
    }

    private static Scope openBudget(TimeoutBudget budget) {
        Baggage baggage = Baggage.builder()
                .put(TimeoutBudget.BAGGAGE_KEY, budget.toBaggageValue())
                .build();
        return baggage.storeInContext(Context.current()).makeCurrent();
    }

    private static final class RecordingChain implements Interceptor.Chain {
        private final Request request;
        private int connectTimeoutMs = 10_000;
        private int readTimeoutMs = 10_000;
        private int writeTimeoutMs = 10_000;
        private Request proceeded =
                new Request.Builder().url("http://example.test/").build();

        private RecordingChain(Request request) {
            this.request = request;
        }

        @Override
        public Request request() {
            return request;
        }

        @Override
        public Response proceed(Request request) {
            this.proceeded = request;
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create("", MediaType.parse("text/plain")))
                    .build();
        }

        @Override
        public @Nullable Connection connection() {
            return null;
        }

        @Override
        public Call call() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int connectTimeoutMillis() {
            return connectTimeoutMs;
        }

        @Override
        public Interceptor.Chain withConnectTimeout(int timeout, TimeUnit unit) {
            connectTimeoutMs = (int) unit.toMillis(timeout);
            return this;
        }

        @Override
        public int readTimeoutMillis() {
            return readTimeoutMs;
        }

        @Override
        public Interceptor.Chain withReadTimeout(int timeout, TimeUnit unit) {
            readTimeoutMs = (int) unit.toMillis(timeout);
            return this;
        }

        @Override
        public int writeTimeoutMillis() {
            return writeTimeoutMs;
        }

        @Override
        public Interceptor.Chain withWriteTimeout(int timeout, TimeUnit unit) {
            writeTimeoutMs = (int) unit.toMillis(timeout);
            return this;
        }
    }
}
