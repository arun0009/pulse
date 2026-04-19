package io.github.arun0009.pulse.db;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate {@link StatementInspector} that records every prepared SQL statement against the
 * active {@link DbObservationContext} scope.
 *
 * <p>Wired through Hibernate's {@code hibernate.session_factory.statement_inspector} property
 * by {@link PulseHibernatePropertiesCustomizer}. {@link #inspect(String)} fires once per
 * {@code PreparedStatement} preparation — synchronously, on the request thread, before the
 * statement executes — which is exactly when an active request scope is available to charge
 * the count against.
 *
 * <p>The inspector returns the SQL <em>unmodified</em>: Pulse never rewrites a user's query.
 * The contract only exists to count and observe.
 *
 * <p>Hibernate constructs the inspector via no-arg reflection from the property value, so the
 * class must have a public no-arg constructor and no Spring-injected state. All state lives
 * in {@link DbObservationContext}'s thread-local, which is the right boundary anyway: an
 * inspector is shared across all sessions, but a request-scoped count must not be.
 */
public class PulseStatementInspector implements StatementInspector {

    @Override
    public String inspect(String sql) {
        DbObservationContext.recordStatement(sql);
        return sql;
    }
}
