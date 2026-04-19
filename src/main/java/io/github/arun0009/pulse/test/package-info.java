/**
 * Pulse test slice — {@link io.github.arun0009.pulse.test.PulseTest @PulseTest}, {@link
 * io.github.arun0009.pulse.test.PulseTestHarness PulseTestHarness}, and {@link
 * io.github.arun0009.pulse.test.PulseTestConfiguration PulseTestConfiguration} for capturing spans
 * and metrics in-memory and asserting on them with AssertJ-style fluent chains.
 *
 * <p>Shipped in the main artifact so consumers don't need a separate {@code -test} jar; all
 * test-time dependencies (JUnit, AssertJ, Spring Boot test, OpenTelemetry SDK testing) are marked
 * <em>optional</em> on the starter so they don't propagate to production classpaths.
 */
@NullMarked
package io.github.arun0009.pulse.test;

import org.jspecify.annotations.NullMarked;
