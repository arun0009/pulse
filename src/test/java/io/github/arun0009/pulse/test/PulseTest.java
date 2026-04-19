package io.github.arun0009.pulse.test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that wires a Pulse-aware test slice: full Pulse auto-config, an in-memory OTel SDK (so
 * spans/metrics are captured without an exporter), and a {@link PulseTestHarness} bean for fluent
 * assertions.
 *
 * <p>Usage:
 *
 * <pre>
 * &#064;PulseTest
 * class OrderServiceTest {
 *     &#064;Autowired PulseTestHarness pulse;
 *     &#064;Autowired OrderService orders;
 *
 *     &#064;Test
 *     void publishes_wide_event() {
 *         orders.place(...);
 *         pulse.assertEvent("order.placed").hasAttribute("amount", 49.99);
 *     }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
        classes = {PulseTestApplication.class, PulseTestConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PulseTestConfiguration.class)
@TestPropertySource(
        properties = {
            "spring.application.name=pulse-test",
            "app.env=test",
            "management.endpoints.web.exposure.include=pulse,health,metrics"
        })
public @interface PulseTest {}
