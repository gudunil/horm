package com.holo.framework.horm.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Minimal {@link DataSourceProvider} that always returns the same
 * {@link Connection}. Used to adapt existing test code that creates a
 * single H2 in-memory connection.
 *
 * <p>Not suitable for production — a real connection pool should be used
 * instead.
 */
public final class SimpleDataSourceProvider implements DataSourceProvider {

    private final Connection connection;

    public SimpleDataSourceProvider(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connection;
    }

    @Override
    public void releaseConnection(Connection connection) {
        // The lifecycle of the single wrapped connection is managed by the
        // caller (typically via HormContext.close()); do not close here.
    }
}
