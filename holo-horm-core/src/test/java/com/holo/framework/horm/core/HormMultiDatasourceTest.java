package com.holo.framework.horm.core;

import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HormMultiDatasourceTest {

    @BeforeEach
    void setUp() {
        // Clear any existing context
        try {
            HormContext.current();
            HormContext.install(null);
        } catch (IllegalStateException e) {
            // No context installed, that's fine
        }
    }

    @AfterEach
    void tearDown() {
        HormContext.install(null);
        TransactionManager.clear();
    }

    @Test
    void installNamedDatasource() throws SQLException {
        Connection conn1 = createMockConnection();
        Connection conn2 = createMockConnection();
        DataSourceProvider provider1 = new SimpleDataSourceProvider(conn1);
        DataSourceProvider provider2 = new SimpleDataSourceProvider(conn2);

        // Install default datasource
        Horm.install(provider1);
        assertThat(Horm.context().dataSourceRegistry().hasDefault()).isTrue();

        // Install named datasource
        Horm.install("secondary", provider2);

        // Verify both are registered
        assertThat(Horm.context().getDataSource("default")).isSameAs(provider1);
        assertThat(Horm.context().getDataSource("secondary")).isSameAs(provider2);
    }

    @Test
    void installNamedDatasourceWithoutContext() throws SQLException {
        Connection conn = createMockConnection();
        DataSourceProvider provider = new SimpleDataSourceProvider(conn);

        // No context installed yet, installOrRegister should create one
        Horm.installOrRegister("primary", provider);

        assertThat(Horm.context()).isNotNull();
        assertThat(Horm.context().getDataSource("primary")).isSameAs(provider);
    }

    @Test
    void installOrRegisterAddsToExistingContext() throws SQLException {
        Connection conn1 = createMockConnection();
        Connection conn2 = createMockConnection();
        DataSourceProvider provider1 = new SimpleDataSourceProvider(conn1);
        DataSourceProvider provider2 = new SimpleDataSourceProvider(conn2);

        // Install default first
        Horm.install(provider1);
        assertThat(Horm.context().dataSourceRegistry().size()).isEqualTo(1);

        // Add named datasource
        Horm.installOrRegister("secondary", provider2);

        // Verify both are registered
        assertThat(Horm.context().dataSourceRegistry().size()).isEqualTo(2);
        assertThat(Horm.context().getDataSource("default")).isSameAs(provider1);
        assertThat(Horm.context().getDataSource("secondary")).isSameAs(provider2);
    }

    @Test
    void installDuplicateNamedDatasourceThrows() throws SQLException {
        Connection conn1 = createMockConnection();
        Connection conn2 = createMockConnection();
        DataSourceProvider provider1 = new SimpleDataSourceProvider(conn1);
        DataSourceProvider provider2 = new SimpleDataSourceProvider(conn2);

        // 先安装默认数据源
        Horm.install(provider1);
        // 然后注册命名数据源
        Horm.install("test", provider1);

        assertThatThrownBy(() -> Horm.install("test", provider2))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already registered");
    }

    @Test
    void contextReturnsInstalledContext() throws SQLException {
        Connection conn = createMockConnection();
        DataSourceProvider provider = new SimpleDataSourceProvider(conn);

        Horm.install(provider);

        assertThat(Horm.context()).isNotNull();
        assertThat(Horm.context().dataSourceProvider()).isSameAs(provider);
    }

    @Test
    void contextWithoutInstallThrows() {
        assertThatThrownBy(Horm::context)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not installed");
    }

    private Connection createMockConnection() {
        return new MockConnection();
    }

    /**
     * Minimal mock Connection for testing. Only implements methods needed
     * by SimpleDataSourceProvider.
     */
    private static class MockConnection implements Connection {
        @Override
        public void close() throws SQLException {
            // No-op
        }

        @Override
        public boolean isClosed() throws SQLException {
            return false;
        }

        // Other methods throw UnsupportedOperationException
        @Override
        public java.sql.Statement createStatement() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.CallableStatement prepareCall(String sql) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public String nativeSQL(String sql) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAutoCommit(boolean autoCommit) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean getAutoCommit() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void commit() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void rollback() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.DatabaseMetaData getMetaData() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setReadOnly(boolean readOnly) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isReadOnly() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setCatalog(String catalog) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getCatalog() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setTransactionIsolation(int level) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getTransactionIsolation() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.SQLWarning getWarnings() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearWarnings() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Map<String, Class<?>> getTypeMap() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setTypeMap(java.util.Map<String, Class<?>> map) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setHoldability(int holdability) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getHoldability() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.Savepoint setSavepoint() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.Savepoint setSavepoint(String name) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void rollback(java.sql.Savepoint savepoint) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.Clob createClob() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.Blob createBlob() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.NClob createNClob() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.SQLXML createSQLXML() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isValid(int timeout) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setClientInfo(String name, String value) throws java.sql.SQLClientInfoException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setClientInfo(java.util.Properties properties) throws java.sql.SQLClientInfoException {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getClientInfo(String name) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Properties getClientInfo() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.sql.Struct createStruct(String typeName, Object[] attributes) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setSchema(String schema) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getSchema() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abort(java.util.concurrent.Executor executor) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getNetworkTimeout() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            throw new UnsupportedOperationException();
        }
    }
}
