package com.holo.framework.horm.benchmark;

import com.holo.framework.horm.core.DataSourceProvider;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * A {@link DataSourceProvider} that returns a no-op JDBC {@link Connection}
 * proxy. Used by micro-benchmarks that want to measure transaction proxy
 * invocation overhead without real database I/O.
 *
 * <p>All {@link Connection} methods are no-ops by default:
 * {@link Connection#commit()}, {@link Connection#rollback()} and
 * {@link Connection#close()} do nothing, and
 * {@link Connection#setAutoCommit(boolean)} is ignored.
 */
public final class NoOpDataSourceProvider implements DataSourceProvider {

    private final Connection connection;

    public NoOpDataSourceProvider() {
        InvocationHandler handler = (proxy, method, args) -> {
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class || returnType == Boolean.class) {
                return Boolean.FALSE;
            }
            if (returnType == int.class || returnType == Integer.class) {
                return Integer.valueOf(0);
            }
            if (returnType == long.class || returnType == Long.class) {
                return Long.valueOf(0L);
            }
            if (returnType == void.class) {
                return null;
            }
            return null;
        };
        this.connection = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            handler
        );
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connection;
    }

    @Override
    public void releaseConnection(Connection connection) {
        // no-op
    }
}
