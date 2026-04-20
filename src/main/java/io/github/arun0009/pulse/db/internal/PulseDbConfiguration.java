package io.github.arun0009.pulse.db.internal;

import io.github.arun0009.pulse.autoconfigure.PulseAutoConfiguration;
import io.github.arun0009.pulse.autoconfigure.PulseRequestMatcherFactory;
import io.github.arun0009.pulse.core.PulseRequestMatcher;
import io.github.arun0009.pulse.db.DbProperties;
import io.github.arun0009.pulse.db.PulseDbObservationFilter;
import io.github.arun0009.pulse.db.PulseHibernatePropertiesCustomizer;
import io.github.arun0009.pulse.db.PulseStatementInspector;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Servlet;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;

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
@AutoConfiguration(after = PulseAutoConfiguration.class)
@ConditionalOnClass({StatementInspector.class, HibernatePropertiesCustomizer.class})
@ConditionalOnProperty(prefix = "pulse.db", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PulseDbConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PulseHibernatePropertiesCustomizer pulseHibernateStatementInspectorCustomizer(DbProperties properties) {
        return new PulseHibernatePropertiesCustomizer(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(Servlet.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public PulseDbObservationFilter pulseDbObservationFilter(
            MeterRegistry meterRegistry,
            DbProperties properties,
            ObjectProvider<PulseRequestMatcherFactory> matcherFactory,
            ObjectProvider<Tracer> tracer) {
        PulseRequestMatcherFactory factory = matcherFactory.getIfAvailable();
        PulseRequestMatcher gate =
                factory == null ? PulseRequestMatcher.ALWAYS : factory.build("db", properties.enabledWhen());
        return new PulseDbObservationFilter(meterRegistry, properties, gate, tracer.getIfAvailable(() -> Tracer.NOOP));
    }
}
