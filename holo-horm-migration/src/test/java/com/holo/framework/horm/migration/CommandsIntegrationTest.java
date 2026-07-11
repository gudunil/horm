package com.holo.framework.horm.migration;

import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.SimpleDataSourceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MigrateCommand} 和 {@link StatusCommand} 集成测试。
 */
class CommandsIntegrationTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:test_cmd;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        HormContext context = new HormContext(new SimpleDataSourceProvider(connection));
        HormContext.install(context);
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
    }

    @Test
    void migrateCommandSucceeds() {
        MigrateCommand cmd = new MigrateCommand();
        MigrationCommand.CommandResult result = cmd.execute(new String[0]);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("Successfully applied");
    }

    @Test
    void statusCommandSucceeds() {
        StatusCommand cmd = new StatusCommand();
        MigrationCommand.CommandResult result = cmd.execute(new String[0]);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("Applied:");
    }

    @Test
    void migrateCommandFailsWithoutContext() {
        HormContext.install(null);
        MigrateCommand cmd = new MigrateCommand();
        MigrationCommand.CommandResult result = cmd.execute(new String[0]);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Migration failed");
    }
}