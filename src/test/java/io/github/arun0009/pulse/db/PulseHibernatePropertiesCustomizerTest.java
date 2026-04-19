package io.github.arun0009.pulse.db;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the customizer wires Hibernate idiomatically and never silently overrides a
 * user's existing setting.
 */
class PulseHibernatePropertiesCustomizerTest {

    private static final String INSPECTOR_KEY = "hibernate.session_factory.statement_inspector";
    private static final String SLOW_QUERY_KEY = "hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS";

    @Test
    void registers_pulse_inspector_when_no_existing_inspector_is_configured() {
        PulseProperties.Db config = new PulseProperties.Db(true, 50, Duration.ofMillis(500));
        Map<String, Object> hibernateProps = new HashMap<>();
        new PulseHibernatePropertiesCustomizer(config).customize(hibernateProps);

        assertThat(hibernateProps).containsEntry(INSPECTOR_KEY, PulseStatementInspector.class.getName());
        assertThat(hibernateProps).containsEntry(SLOW_QUERY_KEY, "500");
    }

    @Test
    void does_not_overwrite_user_configured_statement_inspector() {
        PulseProperties.Db config = new PulseProperties.Db(true, 50, Duration.ofMillis(500));
        Map<String, Object> hibernateProps = new HashMap<>();
        hibernateProps.put(INSPECTOR_KEY, "com.example.MyInspector");

        new PulseHibernatePropertiesCustomizer(config).customize(hibernateProps);

        assertThat(hibernateProps)
                .as("Pulse must respect a user's existing inspector — silently overwriting it"
                        + " would be a surprise the user can't easily debug")
                .containsEntry(INSPECTOR_KEY, "com.example.MyInspector");
    }

    @Test
    void does_not_overwrite_user_configured_slow_query_threshold() {
        PulseProperties.Db config = new PulseProperties.Db(true, 50, Duration.ofMillis(500));
        Map<String, Object> hibernateProps = new HashMap<>();
        hibernateProps.put(SLOW_QUERY_KEY, "5000");

        new PulseHibernatePropertiesCustomizer(config).customize(hibernateProps);

        assertThat(hibernateProps).containsEntry(SLOW_QUERY_KEY, "5000");
    }

    @Test
    void slow_query_threshold_uses_milliseconds_so_the_property_string_matches_hibernate_contract() {
        // Hibernate's property is named LOG_QUERIES_SLOWER_THAN_MS — Pulse's Duration must be
        // converted to a millisecond *string* (Hibernate parses it via Long.parseLong, not as a
        // Duration). Regression here would make Hibernate reject the value at startup.
        PulseProperties.Db config = new PulseProperties.Db(true, 50, Duration.ofSeconds(2));
        Map<String, Object> hibernateProps = new HashMap<>();
        new PulseHibernatePropertiesCustomizer(config).customize(hibernateProps);

        Object value = hibernateProps.get(SLOW_QUERY_KEY);
        assertThat(value).isInstanceOf(String.class).isEqualTo("2000");
    }
}
