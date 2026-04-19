package io.github.arun0009.pulse.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the most-quoted Pulse claim end-to-end: an inbound budget header is consumed, time
 * elapses on the server, and the next outbound call carries the <em>remaining</em> budget — not the
 * original or the platform default.
 *
 * <p>Topology: WireMock stub for the downstream &amp; client → Spring Boot test app that holds the
 * request open for ~200ms then calls WireMock with a {@link RestTemplate}. Asserts the {@code
 * Pulse-Timeout-Ms} the downstream actually received.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = TimeoutBudgetEndToEndIT.TestApp.class)
@TestPropertySource(
        properties = {
            "spring.application.name=pulse-budget-it",
            "app.env=it",
            "pulse.timeout-budget.safety-margin=0ms",
            "pulse.timeout-budget.minimum-budget=10ms"
        })
class TimeoutBudgetEndToEndIT {

    static WireMockServer downstream;

    @LocalServerPort
    int port;

    @Autowired
    RestTemplate restTemplate;

    @BeforeAll
    static void startDownstream() {
        downstream = new WireMockServer(wireMockConfig().dynamicPort());
        downstream.start();
        downstream.stubFor(
                get(urlEqualTo("/leaf")).willReturn(aResponse().withStatus(200).withBody("leaf-ok")));
    }

    @AfterAll
    static void stopDownstream() {
        if (downstream != null) downstream.stop();
    }

    @Test
    void inbound_2000ms_budget_propagates_to_downstream_minus_elapsed() {
        TestApp.downstreamPort = downstream.port();

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("Pulse-Timeout-Ms", "2000");
        org.springframework.http.HttpEntity<Void> req = new org.springframework.http.HttpEntity<>(headers);

        org.springframework.http.ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/edge", org.springframework.http.HttpMethod.GET, req, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        RequestPatternBuilder leafCalls = getRequestedFor(urlEqualTo("/leaf"));
        var captured = downstream.findAll(leafCalls);
        assertThat(captured).hasSize(1);

        String observed = captured.get(0).getHeader("Pulse-Timeout-Ms");
        assertThat(observed)
                .as("downstream must receive the Pulse-Timeout-Ms header")
                .isNotNull();
        long observedMs = Long.parseLong(observed);
        assertThat(observedMs)
                .as("remaining budget must be < inbound budget (some time spent on edge)")
                .isLessThan(2000)
                .isGreaterThan(1500);
    }

    @Test
    void absent_inbound_header_propagates_default_budget_to_downstream() {
        TestApp.downstreamPort = downstream.port();
        downstream.resetRequests();

        restTemplate.getForObject("http://localhost:" + port + "/edge", String.class);

        var captured = downstream.findAll(getRequestedFor(urlEqualTo("/leaf")));
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getHeader("Pulse-Timeout-Ms"))
                .as("a default budget must still propagate when caller did not set one")
                .isNotNull();
        long observedMs = Long.parseLong(captured.get(0).getHeader("Pulse-Timeout-Ms"));
        assertThat(observedMs).isPositive();
    }

    @SpringBootApplication
    static class TestApp {
        static volatile int downstreamPort;

        @Bean
        RestTemplate restTemplate(RestTemplateBuilder builder) {
            return builder.build();
        }

        @RestController
        static class EdgeController {
            private final RestTemplate client;

            EdgeController(RestTemplate client) {
                this.client = client;
            }

            @GetMapping("/edge")
            String edge() throws InterruptedException {
                Thread.sleep(200); // burn some of the inbound budget
                return client.getForObject("http://localhost:" + downstreamPort + "/leaf", String.class);
            }
        }
    }
}
