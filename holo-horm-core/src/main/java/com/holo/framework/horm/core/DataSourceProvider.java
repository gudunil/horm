package com.holo.framework.horm.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * SPI for providing JDBC connections. Allows HORM to work with a
 * {@link javax.sql.DataSource} instead of a single {@link Connection},
 * enabling connection pooling and multi-datasource routing (M5).
 *
 * <p>M4 ships {@link SimpleDataSourceProvider} as a minimal adapter for
 * existing tests that pass a single Connection. Production code should
 * provide an implementation backed by a real connection pool.
 */
@FunctionalInterface
public interface DataSourceProvider {

    /**
     * Returns a connection from the underlying data source.
     *
     * <p>Callers are responsible for closing the connection when done
     * (unless the connection is bound to a transaction scope managed by
     * {@link TransactionManager}).
     *
     * @throws SQLException if a database access error occurs
     */
    Connection getConnection() throws SQLException;
}
