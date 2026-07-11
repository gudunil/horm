package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.DataSourceProvider;
import org.h2.jdbcx.JdbcDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Test DataSourceProvider for unit tests.
 */
public class TestDataSourceProvider implements DataSourceProvider {

    private final JdbcDataSource dataSource;

    public TestDataSourceProvider() {
        this.dataSource = new JdbcDataSource();
        this.dataSource.setURL("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
        this.dataSource.setUser("sa");
        this.dataSource.setPassword("");
    }

    @Override
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseConnection(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                // ignore
            }
        }
    }
}
