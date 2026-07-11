package com.holo.framework.horm.migration;

import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MultiDataSourceMigrationRunner} 单元测试。
 */
class MultiDataSourceMigrationRunnerTest {

    @Test
    void migrateAllWithSingleDatasource() throws SQLException {
        Connection conn = DriverManager.getConnection(
            "jdbc:h2:mem:test_mds;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try {
            DataSourceRegistry registry = new DataSourceRegistry();
            registry.registerDefault(new TestProvider(conn));

            MultiDataSourceMigrationRunner runner = new MultiDataSourceMigrationRunner(
                registry, FlywayMigrationConfig.builder().build());
            Map<String, Integer> results = runner.migrateAll();

            assertThat(results).containsKey(DataSourceRegistry.DEFAULT_NAME);
            assertThat(results.get(DataSourceRegistry.DEFAULT_NAME)).isEqualTo(0);
        } finally {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP ALL OBJECTS");
            } catch (SQLException ignored) {
            }
            conn.close();
        }
    }

    @Test
    void migrateWithSpecificDatasource() throws SQLException {
        Connection conn = DriverManager.getConnection(
            "jdbc:h2:mem:test_mds2;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try {
            DataSourceRegistry registry = new DataSourceRegistry();
            registry.register("custom", new TestProvider(conn));

            MultiDataSourceMigrationRunner runner = new MultiDataSourceMigrationRunner(
                registry, FlywayMigrationConfig.builder().build());
            int count = runner.migrate("custom");

            assertThat(count).isEqualTo(0);
        } finally {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP ALL OBJECTS");
            } catch (SQLException ignored) {
            }
            conn.close();
        }
    }

    @Test
    void migrateWithUnknownNameThrows() {
        DataSourceRegistry registry = new DataSourceRegistry();

        MultiDataSourceMigrationRunner runner = new MultiDataSourceMigrationRunner(
            registry, FlywayMigrationConfig.builder().build());

        assertThatThrownBy(() -> runner.migrate("unknown"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void migrateAllWithMultipleDatasources() throws SQLException {
        Connection conn1 = DriverManager.getConnection(
            "jdbc:h2:mem:test_mds3a;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Connection conn2 = DriverManager.getConnection(
            "jdbc:h2:mem:test_mds3b;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try {
            DataSourceRegistry registry = new DataSourceRegistry();
            registry.register("db1", new TestProvider(conn1));
            registry.register("db2", new TestProvider(conn2));

            MultiDataSourceMigrationRunner runner = new MultiDataSourceMigrationRunner(
                registry, FlywayMigrationConfig.builder().build());
            Map<String, Integer> results = runner.migrateAll();

            assertThat(results).hasSize(2);
            assertThat(results).containsKeys("db1", "db2");
        } finally {
            try (Statement s = conn1.createStatement()) { s.execute("DROP ALL OBJECTS"); } catch (SQLException ignored) {}
            try (Statement s = conn2.createStatement()) { s.execute("DROP ALL OBJECTS"); } catch (SQLException ignored) {}
            conn1.close();
            conn2.close();
        }
    }

    private static class TestProvider implements DataSourceProvider {
        private final Connection conn;
        TestProvider(Connection conn) { this.conn = conn; }
        @Override public Connection getConnection() { return conn; }
        @Override public void releaseConnection(Connection connection) { /* no-op */ }
    }
}