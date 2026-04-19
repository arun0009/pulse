package io.github.arun0009.pulse.db;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;

import java.util.Map;

/**
 * Wires Pulse's {@link PulseStatementInspector} into Hibernate via Spring Boot's
 * {@link HibernatePropertiesCustomizer} extension point. The customizer also seeds Hibernate's
 * built-in slow-query logging threshold so consumers don't have to copy the property name from
 * the Hibernate manual.
 *
 * <p>Both settings are <em>only</em> applied when not already present in the resolved Hibernate
 * properties. This means a user who has explicitly configured their own
 * {@code hibernate.session_factory.statement_inspector} or
 * {@code hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS} keeps their setting — Pulse
 * does not silently override.
 *
 * <p>The slow-query threshold makes Hibernate emit a WARN line on the
 * {@code org.hibernate.SQL_SLOW} logger for every query whose execution exceeds the threshold.
 * Because Pulse's structured JSON layout already attaches {@code traceId}, {@code requestId},
 * and {@code service} to every log line, the slow-query message is automatically correlated
 * with the trace that issued the query — no extra plumbing required.
 */
public class PulseHibernatePropertiesCustomizer implements HibernatePropertiesCustomizer {

    private static final String INSPECTOR_KEY = "hibernate.session_factory.statement_inspector";
    private static final String SLOW_QUERY_KEY = "hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS";

    private final PulseProperties.Db config;

    public PulseHibernatePropertiesCustomizer(PulseProperties.Db config) {
        this.config = config;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.putIfAbsent(INSPECTOR_KEY, PulseStatementInspector.class.getName());
        hibernateProperties.putIfAbsent(
                SLOW_QUERY_KEY, String.valueOf(config.slowQueryThreshold().toMillis()));
    }
}
