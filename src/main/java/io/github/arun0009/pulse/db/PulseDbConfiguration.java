package io.github.arun0009.pulse.db;

import io.github.arun0009.pulse.autoconfigure.PulseProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Servlet;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Pulse's database-observability subsystem when Hibernate ORM is on the classpath. When
 * Hibernate is absent (REST-only services, MongoDB-only apps, JDBC-without-Hibernate stacks),
 * the entire configuration is skipped via {@link ConditionalOnClass} — no startup cost, no
 * bean leakage.
 *
 * <p>Two beans are produced:
 *
 * <ul>
 *   <li>{@link PulseHibernatePropertiesCustomizer} — registers {@link PulseStatementInspector}
 *       on Hibernate and seeds the slow-query threshold. Always registered when Hibernate is
 *       present and {@code pulse.db.enabled=true}.
 *   <li>{@link PulseDbObservationFilter} — only registered when the application is a servlet
 *       web app, since the per-request scope is anchored to {@code HttpServletRequest}.
 *       Background-only apps still get the inspector wired (so the inspector becomes a no-op,
 *       safely) but no filter — there is no request to scope to.
 * </ul>
 *
 * <p>The customizer is the only bean Hibernate actually consumes; consumers who want the
 * inspector but not the filter (e.g. they want to scope statement counts to their own
 * request boundary, like a Kafka listener) can just declare their own filter and the rest of
 * the wiring still works.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({StatementInspector.class, HibernatePropertiesCustomizer.class})
@ConditionalOnProperty(prefix = "pulse.db", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PulseDbConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PulseHibernatePropertiesCustomizer pulseHibernateStatementInspectorCustomizer(PulseProperties properties) {
        return new PulseHibernatePropertiesCustomizer(properties.db());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(Servlet.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public PulseDbObservationFilter pulseDbObservationFilter(MeterRegistry meterRegistry, PulseProperties properties) {
        return new PulseDbObservationFilter(meterRegistry, properties.db());
    }
}
