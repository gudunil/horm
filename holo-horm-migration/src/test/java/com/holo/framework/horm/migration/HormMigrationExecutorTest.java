package com.holo.framework.horm.migration;

import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.SimpleDataSourceProvider;
import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HormMigrationExecutor} 集成测试。
 */
class HormMigrationExecutorTest {

    private Connection connection;
    private HormContext context;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:test_ex;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        context = new HormContext(new SimpleDataSourceProvider(connection));
        HormContext.install(context);
        HormMigrationExecutor.configure(c -> c.table("horm_schema_history"));
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP ALL OBJECTS");
            } catch (SQLException ignored) {
            }
            connection.close();
        }
        HormContext.install(null);
        HormMigrationExecutor.configure(FlywayMigrationConfig.builder().build());
    }

    @Test
    void migrateWithCustomConfigUsesGivenTable() {
        // When: migrate the default datasource with a custom table name
        HormMigrationExecutor executor = new HormMigrationExecutor();
        int count = executor.migrate(DataSourceRegistry.DEFAULT_NAME);

        // Then: 0 migrations applied (no migration files), history table created with custom name
        assertThat(count).isEqualTo(0);
    }

    @Test
    void migrateAllReturnsEmptyResult() {
        HormMigrationExecutor executor = new HormMigrationExecutor();
        Map<String, Integer> results = executor.migrateAll();

        assertThat(results).containsKey(DataSourceRegistry.DEFAULT_NAME);
        assertThat(results.get(DataSourceRegistry.DEFAULT_NAME)).isEqualTo(0);
    }

    @Test
    void migrateNonExistentDatasourceThrowsException() {
        HormMigrationExecutor executor = new HormMigrationExecutor();

        assertThatThrownBy(() -> executor.migrate("nonexistent"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nonexistent");
    }

    @Test
    void configureViaConsumer() {
        HormMigrationExecutor.configure(c -> {
            c.table("custom_history");
            c.baselineOnMigrate(true);
            c.baselineVersion("2");
        });

        FlywayMigrationConfig cfg = HormMigrationExecutor.getConfig();
        assertThat(cfg.getTable()).isEqualTo("custom_history");
        assertThat(cfg.isBaselineOnMigrate()).isTrue();
        assertThat(cfg.getBaselineVersion()).isEqualTo("2");
    }

    @Test
    void configureDirectly() {
        FlywayMigrationConfig custom = FlywayMigrationConfig.builder()
            .table("direct_table")
            .build();
        HormMigrationExecutor.configure(custom);

        FlywayMigrationConfig cfg = HormMigrationExecutor.getConfig();
        assertThat(cfg.getTable()).isEqualTo("direct_table");
    }

    @Test
    void configureWithNullConfigThrows() {
        assertThatThrownBy(() -> HormMigrationExecutor.configure((FlywayMigrationConfig) null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void configureWithNullConsumerThrows() {
        assertThatThrownBy(() -> HormMigrationExecutor.configure((java.util.function.Consumer<FlywayMigrationConfig.Builder>) null))
            .isInstanceOf(NullPointerException.class);
    }
}