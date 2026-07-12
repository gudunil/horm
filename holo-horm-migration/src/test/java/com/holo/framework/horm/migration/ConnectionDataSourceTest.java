package com.holo.framework.horm.migration;

import com.holo.framework.horm.migration.internal.ConnectionDataSource;
import com.holo.framework.horm.migration.internal.NonCloseableConnection;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConnectionDataSource} 单元测试。
 */
class ConnectionDataSourceTest {

    private Connection createConnection() throws SQLException {
        return DriverManager.getConnection(
            "jdbc:h2:mem:test_cds;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Test
    void getConnectionReturnsNonCloseableWrapper() throws SQLException {
        Connection conn = createConnection();
        try {
            ConnectionDataSource ds = new ConnectionDataSource(conn);

            Connection wrapped = ds.getConnection();
            assertThat(wrapped).isInstanceOf(NonCloseableConnection.class);
            assertThat(wrapped.isClosed()).isFalse();

            // close() should be a no-op
            wrapped.close();
            assertThat(wrapped.isClosed()).isFalse();

            // second call to getConnection() returns the same wrapper
            Connection wrapped2 = ds.getConnection();
            assertThat(wrapped2).isSameAs(wrapped);
        } finally {
            conn.close();
        }
    }

    @Test
    void getUnderlyingConnectionReturnsOriginal() throws SQLException {
        Connection conn = createConnection();
        try {
            ConnectionDataSource ds = new ConnectionDataSource(conn);
            assertThat(ds.getUnderlyingConnection()).isSameAs(conn);
        } finally {
            conn.close();
        }
    }

    @Test
    void getConnectionWithCredentialsReturnsSameWrapper() throws SQLException {
        Connection conn = createConnection();
        try {
            ConnectionDataSource ds = new ConnectionDataSource(conn);
            Connection withCreds = ds.getConnection("sa", "");
            assertThat(withCreds).isInstanceOf(NonCloseableConnection.class);
        } finally {
            conn.close();
        }
    }

    @Test
    void dataSourceStandardMethods() throws SQLException {
        Connection conn = createConnection();
        try {
            DataSource ds = new ConnectionDataSource(conn);

            assertThat(ds.getLogWriter()).isNull();
            assertThat(ds.getLoginTimeout()).isZero();
            assertThat(ds.getParentLogger()).isNotNull();

            // These are no-ops but should not throw
            ds.setLogWriter(new PrintWriter(System.out));
            ds.setLoginTimeout(30);
        } finally {
            conn.close();
        }
    }

    @Test
    void isWrapperForAndUnwrap() throws SQLException {
        Connection conn = createConnection();
        try {
            ConnectionDataSource ds = new ConnectionDataSource(conn);
            assertThat(ds.isWrapperFor(ConnectionDataSource.class)).isTrue();
            assertThat(ds.isWrapperFor(String.class)).isFalse();

            ConnectionDataSource unwrapped = ds.unwrap(ConnectionDataSource.class);
            assertThat(unwrapped).isSameAs(ds);
        } finally {
            conn.close();
        }
    }

    @Test
    void nonCloseableConnectionDelegation() throws SQLException {
        Connection conn = createConnection();
        try {
            NonCloseableConnection ncc = new NonCloseableConnection(conn);

            assertThat(ncc.isClosed()).isFalse();
            assertThat(ncc.getAutoCommit()).isTrue();
            assertThat(ncc.getDelegate()).isSameAs(conn);

            // Set/Get delegation
            ncc.setAutoCommit(false);
            assertThat(ncc.getAutoCommit()).isFalse();
            ncc.setAutoCommit(true);

            int originalLevel = ncc.getTransactionIsolation();
            ncc.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(ncc.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            ncc.setTransactionIsolation(originalLevel);

            // Wrapper delegation
            assertThat(ncc.isValid(1)).isTrue();
            // Note: isWrapperFor delegates to the underlying connection implementation
            // which may return false for Connection.class on some drivers
        } finally {
            conn.close();
        }
    }
}