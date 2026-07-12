package com.holo.framework.horm.benchmark;

import com.holo.framework.horm.core.DataSourceProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Minimal DataSourceProvider for benchmark setup.
 */
public class BenchDataSourceProvider implements DataSourceProvider {

    public BenchDataSourceProvider() {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("H2 driver not found", e);
        }
    }

    public static final String JDBC_URL = "jdbc:h2:mem:bench;MODE=MySQL;DB_CLOSE_DELAY=-1";

    @Override
    public Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "");
        conn.setAutoCommit(true);
        return conn;
    }

    @Override
    public void releaseConnection(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }
}
