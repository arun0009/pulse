package io.github.arun0009.pulse.startup.internal;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior of the loud "Pulse is Servlet-only" startup signal.
 *
 * <p>The post-processor is a fail-soft diagnostic: it never throws, never adds a property, and
 * never blocks startup. Its only contract is the WARN log on REACTIVE apps and the suppression
 * escape hatch — both of which we verify here. The project ships log4j2 as the SLF4J binding,
 * so we attach a programmatic log4j-core appender to the {@code pulse.startup} logger rather
 * than relying on Spring Boot's stdout-based output capture (which doesn't see log4j2 events
 * routed to its own appenders).
 */
class PulseWebStackEnvironmentPostProcessorTest {

    private static final String LOGGER_NAME = "pulse.startup";

    private CapturingAppender appender;
    private org.apache.logging.log4j.core.Logger coreLogger;

    @BeforeEach
    void attachAppender() {
        appender = new CapturingAppender();
        appender.start();
        coreLogger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(LOGGER_NAME);
        coreLogger.addAppender(appender);
        coreLogger.setLevel(Level.ALL);
        // Non-additive: the WARN belongs to the test appender only. With additivity the banner
        // would propagate to the parent console appender and spam every mvn test run with a
        // false "PULSE web-stack mismatch" — misleading, because production apps never hit it.
        coreLogger.setAdditive(false);
    }

    @AfterEach
    void detachAppender() {
        coreLogger.removeAppender(appender);
        appender.stop();
    }

    @Nested
    class Reactive_warning {

        @Test
        void emits_warn_when_application_is_reactive() {
            SpringApplication app = new SpringApplication();
            app.setWebApplicationType(WebApplicationType.REACTIVE);

            new PulseWebStackEnvironmentPostProcessor().postProcessEnvironment(new StandardEnvironment(), app);

            assertThat(appender.warnMessages())
                    .as("REACTIVE startup must produce exactly one WARN that names both stacks")
                    .singleElement()
                    .satisfies(message -> {
                        assertThat(message).contains("PULSE web-stack mismatch");
                        assertThat(message).contains("REACTIVE");
                        assertThat(message).contains("SERVLET");
                        assertThat(message).contains(PulseWebStackEnvironmentPostProcessor.SUPPRESS_PROPERTY);
                    });
        }

        @Test
        void suppression_property_silences_the_warning() {
            SpringApplication app = new SpringApplication();
            app.setWebApplicationType(WebApplicationType.REACTIVE);

            StandardEnvironment env = new StandardEnvironment();
            env.getPropertySources()
                    .addFirst(new MapPropertySource(
                            "test", Map.of(PulseWebStackEnvironmentPostProcessor.SUPPRESS_PROPERTY, "true")));

            new PulseWebStackEnvironmentPostProcessor().postProcessEnvironment(env, app);

            assertThat(appender.warnMessages())
                    .as("opt-out flag must suppress the WARN entirely")
                    .isEmpty();
        }
    }

    @Nested
    class Other_stacks_are_silent {

        @Test
        void servlet_app_emits_no_log() {
            SpringApplication app = new SpringApplication();
            app.setWebApplicationType(WebApplicationType.SERVLET);

            new PulseWebStackEnvironmentPostProcessor().postProcessEnvironment(new StandardEnvironment(), app);

            assertThat(appender.warnMessages())
                    .as("SERVLET is the supported stack — there is nothing to warn about")
                    .isEmpty();
        }

        @Test
        void worker_app_emits_no_log() {
            SpringApplication app = new SpringApplication();
            app.setWebApplicationType(WebApplicationType.NONE);

            new PulseWebStackEnvironmentPostProcessor().postProcessEnvironment(new StandardEnvironment(), app);

            assertThat(appender.warnMessages())
                    .as("NONE (worker / batch / CLI) is explicitly supported and must not warn")
                    .isEmpty();
        }
    }

    /**
     * Minimal in-memory log4j-core appender used to capture {@code pulse.startup} events for
     * assertion. Avoids pulling in log4j-core's test-jar (not on the runtime classpath) and any
     * coupling to Logback's {@code ListAppender} (which can't be cast onto a log4j2 logger).
     */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = new ArrayList<>();

        CapturingAppender() {
            super(
                    "PulseWebStackTestAppender",
                    null,
                    null,
                    true,
                    org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<String> warnMessages() {
            return events.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(e -> e.getMessage().getFormattedMessage())
                    .toList();
        }
    }
}
