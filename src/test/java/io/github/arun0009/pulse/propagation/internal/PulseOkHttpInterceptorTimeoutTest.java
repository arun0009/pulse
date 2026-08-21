package io.github.arun0009.pulse.propagation.internal;

import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherProperties;
import io.github.arun0009.pulse.guardrails.TimeoutBudget;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetOutbound;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetProperties;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uses a real OkHttp {@code Interceptor.Chain} (a terminal interceptor that never hits the
 * network). OkHttp 5.4 added many Chain methods; a hand-rolled fake no longer compiles.
 */
class PulseOkHttpInterceptorTimeoutTest {

    @Test
    void apply_client_timeout_caps_connect_read_and_write() throws IOException {
        RecordedCall recorded = execute(true);

        assertThat(recorded.connectTimeoutMs).isPositive().isLessThan(10_000);
        assertThat(recorded.readTimeoutMs).isEqualTo(recorded.connectTimeoutMs);
        assertThat(recorded.writeTimeoutMs).isEqualTo(recorded.connectTimeoutMs);
        assertThat(recorded.proceeded.header("Pulse-Timeout-Ms")).isNotBlank();
        assertThat(recorded.proceeded.header(TimeoutBudget.DEADLINE_HEADER)).isNotBlank();
    }

    @Test
    void default_does_not_rewrite_okhttp_timeouts() throws IOException {
        RecordedCall recorded = execute(false);

        assertThat(recorded.connectTimeoutMs).isEqualTo(10_000);
        assertThat(recorded.readTimeoutMs).isEqualTo(10_000);
        assertThat(recorded.writeTimeoutMs).isEqualTo(10_000);
    }

    private static RecordedCall execute(boolean applyClientTimeout) throws IOException {
        TimeoutBudgetOutbound helper = new TimeoutBudgetOutbound(null, props(applyClientTimeout));
        OkHttpPropagationConfiguration.PulseOkHttpInterceptor interceptor =
                new OkHttpPropagationConfiguration.PulseOkHttpInterceptor(Map.of(), "Pulse-Timeout-Ms", true, helper);

        AtomicInteger connectTimeoutMs = new AtomicInteger();
        AtomicInteger readTimeoutMs = new AtomicInteger();
        AtomicInteger writeTimeoutMs = new AtomicInteger();
        AtomicReference<Request> proceeded = new AtomicReference<>();

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(interceptor)
                .addInterceptor(chain -> {
                    connectTimeoutMs.set(chain.connectTimeoutMillis());
                    readTimeoutMs.set(chain.readTimeoutMillis());
                    writeTimeoutMs.set(chain.writeTimeoutMillis());
                    proceeded.set(chain.request());
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create("", MediaType.parse("text/plain")))
                            .build();
                })
                .build();

        // Open the budget after the client is built — OkHttpClient construction is slow on a cold
        // JVM and would otherwise eat most of an 800ms remaining window.
        TimeoutBudget budget = TimeoutBudget.withRemaining(Duration.ofSeconds(2));
        Request request = new Request.Builder().url("http://example.test/stock").build();
        try (Scope ignored = openBudget(budget);
                Response response = client.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
        }

        return new RecordedCall(connectTimeoutMs.get(), readTimeoutMs.get(), writeTimeoutMs.get(), proceeded.get());
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

    private record RecordedCall(int connectTimeoutMs, int readTimeoutMs, int writeTimeoutMs, Request proceeded) {}
}
