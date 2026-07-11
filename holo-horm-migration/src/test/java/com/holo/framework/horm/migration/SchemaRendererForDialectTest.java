package com.holo.framework.horm.migration;

import com.holo.framework.horm.core.dialect.Dialect;
import com.holo.framework.horm.core.dialect.H2Dialect;
import com.holo.framework.horm.core.dialect.MySqlDialect;
import com.holo.framework.horm.core.dialect.PostgresDialect;
import com.holo.framework.horm.migration.internal.MySQLSchemaRenderer;
import com.holo.framework.horm.migration.internal.PostgresSchemaRenderer;
import com.holo.framework.horm.migration.internal.SchemaRenderer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SchemaRenderer#forDialect(Dialect)} 工厂方法测试。
 */
class SchemaRendererForDialectTest {

    @Test
    void forDialectPostgresReturnsPostgresRenderer() {
        Dialect dialect = new PostgresDialect();
        SchemaRenderer renderer = SchemaRenderer.forDialect(dialect);

        assertThat(renderer).isInstanceOf(PostgresSchemaRenderer.class);
    }

    @Test
    void forDialectMySqlReturnsMySQLRenderer() {
        Dialect dialect = new MySqlDialect();
        SchemaRenderer renderer = SchemaRenderer.forDialect(dialect);

        assertThat(renderer).isInstanceOf(MySQLSchemaRenderer.class);
    }

    @Test
    void forDialectH2DefaultReturnsMySQLRenderer() {
        Dialect dialect = new H2Dialect("mysql");
        SchemaRenderer renderer = SchemaRenderer.forDialect(dialect);

        assertThat(renderer).isInstanceOf(MySQLSchemaRenderer.class);
    }

    @Test
    void forDialectH2PostgreSQLModeReturnsPostgresRenderer() {
        Dialect dialect = new H2Dialect("postgresql");
        SchemaRenderer renderer = SchemaRenderer.forDialect(dialect);

        assertThat(renderer).isInstanceOf(PostgresSchemaRenderer.class);
    }

    @Test
    void forDialectH2PostgreSQLModeRendersPostgresDDL() {
        Dialect dialect = new H2Dialect("postgresql");
        SchemaRenderer renderer = SchemaRenderer.forDialect(dialect);

        // Verify the rendered DDL matches PostgreSQL syntax, not MySQL
        String dropIndex = renderer.renderDropIndex("idx_test", "users");
        assertThat(dropIndex).isEqualTo("DROP INDEX \"idx_test\"");

        String renameTable = renderer.renderRenameTable("old", "new");
        assertThat(renameTable).isEqualTo("ALTER TABLE \"old\" RENAME TO \"new\"");

        String renameColumn = renderer.renderRenameColumn("users", "old_col", "new_col");
        assertThat(renameColumn).isEqualTo("ALTER TABLE \"users\" RENAME COLUMN \"old_col\" TO \"new_col\"");
    }

    @Test
    void forDialectH2DefaultRendersMySQLDDL() {
        Dialect dialect = new H2Dialect("mysql");
        SchemaRenderer renderer = SchemaRenderer.forDialect(dialect);

        // Verify the rendered DDL matches MySQL syntax
        String dropIndex = renderer.renderDropIndex("idx_test", "users");
        assertThat(dropIndex).isEqualTo("DROP INDEX idx_test ON users");

        String renameTable = renderer.renderRenameTable("old", "new");
        assertThat(renameTable).isEqualTo("RENAME TABLE old TO new");
    }
}
