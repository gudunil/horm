package com.holo.framework.horm.migration;

import com.holo.framework.horm.migration.internal.ColumnDefinition;
import com.holo.framework.horm.migration.internal.DefaultTableBuilder;
import com.holo.framework.horm.migration.internal.ForeignKeyDefinition;
import com.holo.framework.horm.migration.internal.PostgresSchemaRenderer;
import com.holo.framework.horm.migration.internal.TableDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL Schema 渲染器单元测试。
 */
class PostgresSchemaRendererTest {

    private final PostgresSchemaRenderer renderer = new PostgresSchemaRenderer();

    // --- renderCreateTable ---

    @Test
    void renderCreateTableSimple() {
        TableDefinition table = new TableDefinition("users");
        DefaultTableBuilder builder = new DefaultTableBuilder(table);
        builder.bigIncrements("id");
        builder.string("email", 128).notNull().unique();

        String ddl = renderer.renderCreateTable(table);

        assertThat(ddl).contains("CREATE TABLE \"users\"");
        assertThat(ddl).contains("\"id\" BIGSERIAL NOT NULL PRIMARY KEY");
        assertThat(ddl).contains("\"email\" VARCHAR(128) NOT NULL UNIQUE");
        assertThat(ddl).doesNotContain("ENGINE=InnoDB");
        assertThat(ddl).doesNotContain("DEFAULT CHARSET=utf8mb4");
    }

    @Test
    void renderCreateTableNoEngineSuffix() {
        TableDefinition table = new TableDefinition("orders");
        DefaultTableBuilder builder = new DefaultTableBuilder(table);
        builder.bigIncrements("id");

        String ddl = renderer.renderCreateTable(table);

        assertThat(ddl).endsWith(")");
        assertThat(ddl).doesNotContain("ENGINE");
        assertThat(ddl).doesNotContain("CHARSET");
    }

    @Test
    void renderCreateTableWithTimestamps() {
        TableDefinition table = new TableDefinition("posts");
        DefaultTableBuilder builder = new DefaultTableBuilder(table);
        builder.bigIncrements("id");
        builder.string("title", 255).notNull();
        builder.timestamps();

        String ddl = renderer.renderCreateTable(table);

        // DATETIME should be rendered as TIMESTAMP in PostgreSQL
        assertThat(ddl).contains("\"created_at\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        assertThat(ddl).contains("\"updated_at\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
    }

    @Test
    void renderCreateTableWithForeignKey() {
        TableDefinition table = new TableDefinition("orders");
        DefaultTableBuilder builder = new DefaultTableBuilder(table);
        builder.bigIncrements("id");
        builder.bigInteger("user_id").notNull();
        table.addForeignKey(new ForeignKeyDefinition("user_id", "users", "id"));

        String ddl = renderer.renderCreateTable(table);

        assertThat(ddl).contains("FOREIGN KEY (\"user_id\") REFERENCES \"users\"(\"id\")");
    }

    @Test
    void renderCreateTableWithBoolean() {
        TableDefinition table = new TableDefinition("users");
        DefaultTableBuilder builder = new DefaultTableBuilder(table);
        builder.bigIncrements("id");
        builder.boolean_("active");

        String ddl = renderer.renderCreateTable(table);

        assertThat(ddl).contains("\"active\" BOOLEAN");
    }

    @Test
    void renderCreateTableWithDecimal() {
        TableDefinition table = new TableDefinition("products");
        DefaultTableBuilder builder = new DefaultTableBuilder(table);
        builder.decimal("price", 10, 2).notNull();

        String ddl = renderer.renderCreateTable(table);

        assertThat(ddl).contains("\"price\" DECIMAL(10,2) NOT NULL");
    }

    // --- renderAddColumn ---

    @Test
    void renderAddColumn() {
        ColumnDefinition col = new ColumnDefinition("phone", "VARCHAR");
        col.setLength(20);

        String ddl = renderer.renderAddColumn("users", col);

        assertThat(ddl).isEqualTo("ALTER TABLE \"users\" ADD COLUMN \"phone\" VARCHAR(20)");
    }

    // --- renderDropColumn ---

    @Test
    void renderDropColumn() {
        String ddl = renderer.renderDropColumn("users", "phone");

        assertThat(ddl).isEqualTo("ALTER TABLE \"users\" DROP COLUMN \"phone\"");
    }

    // --- renderModifyColumn ---

    @Test
    void renderModifyColumn() {
        ColumnDefinition col = new ColumnDefinition("email", "VARCHAR");
        col.setLength(256);
        col.setNullable(false);

        String ddl = renderer.renderModifyColumn("users", col);

        assertThat(ddl).isEqualTo("ALTER TABLE \"users\" ALTER COLUMN \"email\" TYPE VARCHAR(256)");
    }

    @Test
    void renderModifyColumnDecimal() {
        ColumnDefinition col = new ColumnDefinition("price", "DECIMAL");
        col.setPrecision(12);
        col.setScale(4);

        String ddl = renderer.renderModifyColumn("products", col);

        assertThat(ddl).isEqualTo("ALTER TABLE \"products\" ALTER COLUMN \"price\" TYPE DECIMAL(12,4)");
    }

    // --- renderDropTable ---

    @Test
    void renderDropTable() {
        String ddl = renderer.renderDropTable("users");

        assertThat(ddl).isEqualTo("DROP TABLE \"users\"");
    }

    // --- renderCreateIndex ---

    @Test
    void renderCreateIndex() {
        String ddl = renderer.renderCreateIndex("idx_users_email", "users", "email");

        assertThat(ddl).isEqualTo("CREATE INDEX \"idx_users_email\" ON \"users\" (\"email\")");
    }

    @Test
    void renderCreateIndexMultipleColumns() {
        String ddl = renderer.renderCreateIndex("idx_users_name_email", "users", "name", "email");

        assertThat(ddl).isEqualTo("CREATE INDEX \"idx_users_name_email\" ON \"users\" (\"name\", \"email\")");
    }

    // --- renderDropIndex ---

    @Test
    void renderDropIndex() {
        String ddl = renderer.renderDropIndex("idx_users_email", "users");

        // PostgreSQL: DROP INDEX does NOT include ON tableName
        assertThat(ddl).isEqualTo("DROP INDEX \"idx_users_email\"");
    }

    // --- renderRenameTable ---

    @Test
    void renderRenameTable() {
        String ddl = renderer.renderRenameTable("users", "user_accounts");

        // PostgreSQL: ALTER TABLE ... RENAME TO ...
        assertThat(ddl).isEqualTo("ALTER TABLE \"users\" RENAME TO \"user_accounts\"");
    }

    // --- renderRenameColumn ---

    @Test
    void renderRenameColumn() {
        String ddl = renderer.renderRenameColumn("users", "email", "email_address");

        // PostgreSQL: RENAME COLUMN ... TO ...
        assertThat(ddl).isEqualTo("ALTER TABLE \"users\" RENAME COLUMN \"email\" TO \"email_address\"");
    }

    // --- renderForeignKey ---

    @Test
    void renderForeignKeyNoConstraintPrefix() {
        ForeignKeyDefinition fk = new ForeignKeyDefinition("user_id", "users", "id");
        String ddl = renderer.renderForeignKey(fk);

        // PostgreSQL: no CONSTRAINT fk_xxx prefix (unlike MySQL)
        assertThat(ddl).isEqualTo("FOREIGN KEY (\"user_id\") REFERENCES \"users\"(\"id\")");
    }

    // --- renderColumnDefinition ---

    @Test
    void renderColumnDefinitionBigSerial() {
        ColumnDefinition col = new ColumnDefinition("id", "BIGINT");
        col.setPrimaryKey(true);
        col.setAutoIncrement(true);
        col.setNullable(false);

        String ddl = renderer.renderColumnDefinition(col);

        assertThat(ddl).contains("BIGSERIAL");
        assertThat(ddl).contains("NOT NULL");
        assertThat(ddl).contains("PRIMARY KEY");
        assertThat(ddl).doesNotContain("AUTO_INCREMENT");
    }

    @Test
    void renderColumnDefinitionSerial() {
        ColumnDefinition col = new ColumnDefinition("id", "INT");
        col.setPrimaryKey(true);
        col.setAutoIncrement(true);
        col.setNullable(false);

        String ddl = renderer.renderColumnDefinition(col);

        assertThat(ddl).contains("SERIAL");
        assertThat(ddl).doesNotContain("AUTO_INCREMENT");
    }

    @Test
    void renderColumnDefinitionBoolean() {
        ColumnDefinition col = new ColumnDefinition("active", "BOOLEAN");

        String ddl = renderer.renderColumnDefinition(col);

        assertThat(ddl).contains("\"active\" BOOLEAN");
    }

    @Test
    void renderColumnDefinitionTimestamp() {
        ColumnDefinition col = new ColumnDefinition("created_at", "DATETIME");
        col.setNullable(false);
        col.setDefaultValue("CURRENT_TIMESTAMP");

        String ddl = renderer.renderColumnDefinition(col);

        // DATETIME should be rendered as TIMESTAMP
        assertThat(ddl).contains("\"created_at\" TIMESTAMP");
        assertThat(ddl).contains("NOT NULL");
        assertThat(ddl).contains("DEFAULT CURRENT_TIMESTAMP");
    }

    @Test
    void renderColumnDefinitionNoComment() {
        ColumnDefinition col = new ColumnDefinition("status", "VARCHAR");
        col.setLength(16);
        col.setComment("用户状态");

        String ddl = renderer.renderColumnDefinition(col);

        // PostgreSQL does not support COMMENT on column inline
        assertThat(ddl).doesNotContain("COMMENT");
    }

    @Test
    void renderColumnDefinitionWithDefaultValue() {
        ColumnDefinition col = new ColumnDefinition("status", "VARCHAR");
        col.setLength(16);
        col.setDefaultValue("'active'");

        String ddl = renderer.renderColumnDefinition(col);

        assertThat(ddl).contains("DEFAULT 'active'");
    }

    @Test
    void renderColumnDefinitionNotNullUnique() {
        ColumnDefinition col = new ColumnDefinition("email", "VARCHAR");
        col.setLength(255);
        col.setNullable(false);
        col.setUnique(true);

        String ddl = renderer.renderColumnDefinition(col);

        assertThat(ddl).contains("NOT NULL");
        assertThat(ddl).contains("UNIQUE");
    }

    @Test
    void renderColumnDefinitionNonAutoIncrementBigInt() {
        ColumnDefinition col = new ColumnDefinition("count", "BIGINT");
        col.setNullable(false);

        String ddl = renderer.renderColumnDefinition(col);

        // Non-auto-increment BIGINT should stay as BIGINT, not BIGSERIAL
        assertThat(ddl).contains("\"count\" BIGINT NOT NULL");
        assertThat(ddl).doesNotContain("BIGSERIAL");
    }

    @Test
    void renderColumnTypeTimestampFromDatetime() {
        ColumnDefinition col = new ColumnDefinition("created_at", "DATETIME");

        String ddl = renderer.renderColumnDefinition(col);

        assertThat(ddl).contains("TIMESTAMP");
        assertThat(ddl).doesNotContain("DATETIME");
    }

    @Test
    void renderColumnTypeTimestampStaysTimestamp() {
        ColumnDefinition col = new ColumnDefinition("event_time", "TIMESTAMP");

        String ddl = renderer.renderColumnDefinition(col);

        assertThat(ddl).contains("\"event_time\" TIMESTAMP");
    }
}
