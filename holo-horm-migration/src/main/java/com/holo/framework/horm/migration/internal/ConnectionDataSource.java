package com.holo.framework.horm.migration.internal;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Wraps a single {@link Connection} as a {@link DataSource} for Flyway.
 *
 * <p>Flyway requires a {@code DataSource}, but HORM's {@code DataSourceProvider}
 * works with individual connections. This adapter bridges the two.
 *
 * <p>The returned connection is wrapped in a non-closeable adapter so that
 * Flyway's internal connection release does not close the underlying connection,
 * which is still owned by the caller.
 */
public final class ConnectionDataSource implements DataSource {

    private final Connection connection;
    private final NonCloseableConnection nonCloseable;

    public ConnectionDataSource(Connection connection) {
        this.connection = connection;
        this.nonCloseable = new NonCloseableConnection(connection);
    }

    @Override
    public Connection getConnection() {
        return nonCloseable;
    }

    @Override
    public Connection getConnection(String username, String password) {
        return nonCloseable;
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("ConnectionDataSource");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Cannot unwrap to " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    /**
     * Returns the underlying original connection.
     */
    public Connection getUnderlyingConnection() {
        return connection;
    }
}
