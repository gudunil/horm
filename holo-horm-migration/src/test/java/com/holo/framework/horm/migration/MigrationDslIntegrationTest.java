package com.holo.framework.horm.migration;

import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.SimpleDataSourceProvider;
import com.holo.framework.horm.migration.internal.DdlSchema;
import com.holo.framework.horm.migration.internal.H2SchemaRenderer;
import com.holo.framework.horm.migration.internal.NonCloseableConnection;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration DSL 集成测试（H2 数据库）。
 */
class MigrationDslIntegrationTest {

    private Connection connection;
    private HormContext context;
    private TestDataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:test_migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        dataSource = new TestDataSource(connection);
        context = new HormContext(new SimpleDataSourceProvider(connection));
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
    void migrationDslCreatesTable() throws SQLException {
        FlywayMigrationConfig config = FlywayMigrationConfig.builder()
            .addJavaMigration(new V1__CreateUsersTable())
            .build();

        FlywayMigrationRunner runner = new FlywayMigrationRunner(dataSource, config);
        int count = runner.migrate();

        assertThat(count).isEqualTo(1);

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
            assertThat(rs.next()).isTrue();
        }
    }

    @Test
    void migrationDslWithMultipleStatements() throws SQLException {
        FlywayMigrationConfig config = FlywayMigrationConfig.builder()
            .addJavaMigration(new V1__CreateUsersTable())
            .addJavaMigration(new V2__AddPhoneColumn())
            .build();

        FlywayMigrationRunner runner = new FlywayMigrationRunner(dataSource, config);
        int count = runner.migrate();

        assertThat(count).isEqualTo(2);

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT phone FROM users")) {
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void migrationStatusReportsAppliedMigrations() {
        FlywayMigrationConfig config = FlywayMigrationConfig.builder()
            .addJavaMigration(new V1__CreateUsersTable())
            .build();

        FlywayMigrationRunner runner = new FlywayMigrationRunner(dataSource, config);
        runner.migrate();

        MigrationStatus status = runner.status();
        assertThat(status.appliedCount()).isEqualTo(1);
        assertThat(status.pendingCount()).isEqualTo(0);
    }

    @Test
    void migrationChecksumDetectsChanges() {
        byte[] content1 = "CREATE TABLE users (id BIGINT PRIMARY KEY)".getBytes();
        byte[] content2 = "CREATE TABLE users (id INT PRIMARY KEY)".getBytes();

        int checksum1 = MigrationChecksum.compute(content1);
        int checksum2 = MigrationChecksum.compute(content2);

        assertThat(checksum1).isNotEqualTo(checksum2);
    }

    static class V1__CreateUsersTable extends BaseJavaMigration {
        @Override
        public void migrate(Context context) throws Exception {
            DdlSchema schema = new DdlSchema(new H2SchemaRenderer());
            schema.createTable("users", t -> {
                t.bigIncrements("id");
                t.string("email", 128).notNull().unique();
                t.string("password", 128).notNull();
                t.timestamps();
            });

            try (Statement stmt = context.getConnection().createStatement()) {
                for (String sql : schema.getStatements()) {
                    stmt.execute(sql);
                }
            }
        }
    }

    static class V2__AddPhoneColumn extends BaseJavaMigration {
        @Override
        public void migrate(Context context) throws Exception {
            DdlSchema schema = new DdlSchema(new H2SchemaRenderer());
            schema.alterTable("users", t -> {
                t.string("phone", 20).nullable();
            });

            try (Statement stmt = context.getConnection().createStatement()) {
                for (String sql : schema.getStatements()) {
                    stmt.execute(sql);
                }
            }
        }
    }

    /**
     * Simple DataSource wrapper around a single Connection for testing.
     * Returns a non-closeable wrapper so Flyway cannot close the underlying connection.
     */
    static class TestDataSource implements DataSource {
        private final Connection connection;
        private final NonCloseableConnection nonCloseable;

        TestDataSource(Connection connection) {
            this.connection = connection;
            this.nonCloseable = new NonCloseableConnection(connection);
        }

        @Override
        public Connection getConnection() {
            return nonCloseable;
        }

        @Override
        public Connection getConnection(String username, String password) {
            return nonCloseable;
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("TestDataSource");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("Cannot unwrap to " + iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
