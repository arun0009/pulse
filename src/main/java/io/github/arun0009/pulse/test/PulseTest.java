package io.github.arun0009.pulse.test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that wires a Pulse-aware test slice on top of {@link SpringBootTest}: full Pulse
 * auto-config, an in-memory OpenTelemetry SDK (so spans and span events are captured without an
 * exporter), and a {@link PulseTestHarness} bean for fluent assertions.
 *
 * <p>The annotation does <b>not</b> declare {@code classes = …}; Spring Boot's standard test
 * discovery walks up from the test class until it finds a {@code @SpringBootConfiguration} (your
 * {@code @SpringBootApplication}). To override, supply your own {@code @SpringBootTest(classes = …)}
 * alongside this annotation or at the test method level.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * @PulseTest
 * class OrderServiceTest {
 *
 *     @Autowired
 *     PulseTestHarness pulse;
 *
 *     @Autowired
 *     OrderService orders;
 *
 *     @Test
 *     void emitsBusinessEvent() {
 *         orders.place(/* ... *\/);
 *         pulse.assertEvent("order.placed").hasAttribute("amount", 49.99);
 *     }
 * }
 * }</pre>
 *
 * <p>Pulled in by adding the {@code pulse-spring-boot-starter} dependency — no separate test
 * artifact required. JUnit, AssertJ, Spring Boot test, and OpenTelemetry SDK testing are declared
 * as <em>optional</em> on the starter, so they don't pollute production classpaths but are
 * available wherever {@code spring-boot-starter-test} is on the test scope (which is everywhere
 * Spring Boot apps are tested).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PulseTestConfiguration.class)
@TestPropertySource(
        properties = {
            "spring.application.name=pulse-test",
            "app.env=test",
            "management.endpoints.web.exposure.include=pulse,pulseui,health,metrics"
        })
public @interface PulseTest {}
