package com.holo.framework.horm.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Ambient runtime context holding the active connection source (and, in
 * later milestones, the transaction handle and cache chain).
 *
 * <p>A single context is installed process-wide via {@link #install(HormContext)}
 * and retrieved by {@link Model} / repository code through {@link #current()}.
 *
 * <p>M4 introduces {@link DataSourceProvider} support alongside the original
 * {@link Connection}-based constructor. When a {@code DataSourceProvider} is
 * available, {@link TransactionManager#currentConnection(HormContext)} prefers
 * it over the legacy single-connection path, enabling proper connection pooling
 * and transaction-scoped connection binding.
 *
 * <p>{@code HormContext} is {@link AutoCloseable}; closing it closes the
 * underlying connection (if created from a single-connection constructor).
 * Contexts created from a {@code DataSourceProvider} do <em>not</em> close
 * the provider on {@code close()} — the provider lifecycle is managed
 * externally.
 */
public final class HormContext implements AutoCloseable {

    private static volatile HormContext current;

    private final Connection connection;
    private final DataSourceProvider dataSourceProvider;

    /**
     * Legacy constructor that wraps a single {@link Connection} in a
     * {@link SimpleDataSourceProvider}. Behaves identically to M1-M3.
     */
    public HormContext(Connection connection) {
        this.connection = connection;
        this.dataSourceProvider = new SimpleDataSourceProvider(connection);
    }

    /**
     * Creates a context backed by a {@link DataSourceProvider}. The provider
     * is the preferred source for connections; the legacy {@link #connection()}
     * method delegates to {@link DataSourceProvider#getConnection()}.
     */
    public HormContext(DataSourceProvider dataSourceProvider) {
        this.connection = null;
        this.dataSourceProvider = dataSourceProvider;
    }

    /**
     * Returns the JDBC connection. When a {@link DataSourceProvider} is set,
     * delegates to {@link DataSourceProvider#getConnection()}; otherwise
     * returns the connection supplied at construction.
     *
     * <p>For transaction-aware connection resolution, prefer
     * {@link TransactionManager#currentConnection(HormContext)} which
     * returns the thread-bound transaction connection when active.
     */
    public Connection connection() {
        if (dataSourceProvider != null) {
            try {
                return dataSourceProvider.getConnection();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to obtain connection from DataSourceProvider", e);
            }
        }
        return connection;
    }

    /** Returns the {@link DataSourceProvider}, or {@code null} if not set. */
    public DataSourceProvider dataSourceProvider() {
        return dataSourceProvider;
    }

    /**
     * Releases a connection obtained from this context's
     * {@link DataSourceProvider} when it is no longer needed.
     *
     * <p>For the legacy single-connection context this is a no-op because the
     * underlying connection is closed by {@link #close()}.
     */
    public void releaseConnection(Connection connection) {
        if (dataSourceProvider != null) {
            dataSourceProvider.releaseConnection(connection);
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to close HormContext", e);
            }
        }
    }

    /**
     * Installs {@code ctx} as the process-wide current context. The previous
     * context (if any) is replaced without closing — the caller is
     * responsible for lifecycle management of the prior instance.
     */
    public static void install(HormContext ctx) {
        current = ctx;
    }

    /**
     * Returns the installed context.
     *
     * @throws IllegalStateException if no context has been installed via
     *         {@link #install(HormContext)} (or {@link Horm#install(HormContext)})
     *         yet
     */
    public static HormContext current() {
        HormContext ctx = current;
        if (ctx == null) {
            throw new IllegalStateException(
                "HormContext not installed; call Horm.install(ctx) first");
        }
        return ctx;
    }
}
