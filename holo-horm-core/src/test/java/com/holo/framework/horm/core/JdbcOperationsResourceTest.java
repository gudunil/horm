package com.holo.framework.horm.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.holo.framework.horm.core.datasource.DataSourceRegistry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for resource-management robustness in {@link JdbcOperations}:
 * failures during connection release must not swallow the original
 * database exception.
 */
class JdbcOperationsResourceTest {

    @AfterEach
    void tearDown() {
        HormContext.install(null);
    }

    @Test
    void releaseFailureDoesNotSwallowOriginalSqlException() throws SQLException {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenThrow(new SQLException("query failed"));

        DataSourceProvider provider = new DataSourceProvider() {
            @Override
            public Connection getConnection() {
                return conn;
            }

            @Override
            public void releaseConnection(Connection connection) {
                throw new RuntimeException("release failed");
            }
        };

        HormContext ctx = new HormContext(provider);
        Horm.install(ctx);

        HormException thrown = org.junit.jupiter.api.Assertions.assertThrows(
            HormException.class,
            () -> JdbcOperations.query(ctx, DataSourceRegistry.DEFAULT_NAME, "SELECT 1", List.of(),
                ResultSet::next, "run query"));

        assertThat(thrown).hasMessageContaining("query failed");
        assertThat(thrown.getSuppressed()).hasSize(1);
        assertThat(thrown.getSuppressed()[0]).hasMessageContaining("release failed");
    }
}
