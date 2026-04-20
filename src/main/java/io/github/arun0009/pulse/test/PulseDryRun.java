package io.github.arun0009.pulse.test;

import io.github.arun0009.pulse.enforcement.PulseEnforcementMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Composed test slice that boots a Spring application with Pulse pinned to
 * {@link PulseEnforcementMode.Mode#DRY_RUN DRY_RUN} for the duration of the test class. The
 * in-memory OTel/meter test harness ({@link PulseTestConfiguration}) is imported automatically
 * so a single {@code @PulseDryRun} on the test class is everything you need to start asserting
 * "what would Pulse have done?" against real production code.
 *
 * <h2>What dry-run buys you</h2>
 *
 * <ul>
 *   <li>The trace-context guard records {@code pulse.trace.missing} but never throws — a test
 *       calling an endpoint without {@code traceparent} can assert that Pulse <em>noticed</em>
 *       without dealing with HTTP 400s.
 *   <li>The cardinality firewall increments {@code pulse.cardinality.overflow} but lets the
 *       runaway tag value pass through — a test that intentionally generates 5000 distinct
 *       userIds can assert "my new endpoint is leaking userId into a counter" without paying
 *       the cost of bucketed metrics elsewhere.
 *   <li>Every other Pulse subsystem stays on its normal code path.
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * @PulseDryRun
 * class TraceContextRegressionTest {
 *
 *     @Autowired PulseTestHarness pulse;
 *     @Autowired TestRestTemplate http;
 *
 *     @Test
 *     void calling_orders_without_traceparent_increments_pulse_trace_missing() {
 *         http.getForEntity("/orders/42", String.class);
 *         assertThat(pulse.counterValue("pulse.trace.missing", "route", "/orders/{id}"))
 *             .isEqualTo(1.0);
 *     }
 * }
 * }</pre>
 *
 * <p>{@code @PulseDryRun} composes a {@link SpringBootTest @SpringBootTest} with the in-memory
 * Pulse test harness and a {@link TestPropertySource} that flips
 * {@code pulse.enforcement.mode=DRY_RUN}. If you want a different web environment (e.g.
 * {@code RANDOM_PORT}), declare your own {@code @SpringBootTest} alongside this annotation —
 * Spring's annotation merging picks the most specific value.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import(PulseTestConfiguration.class)
@TestPropertySource(properties = "pulse.enforcement.mode=DRY_RUN")
public @interface PulseDryRun {}
