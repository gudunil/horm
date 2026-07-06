package com.holo.framework.horm.migration;

import com.holo.framework.horm.migration.internal.ColumnDefinition;
import com.holo.framework.horm.migration.internal.DefaultTableBuilder;
import com.holo.framework.horm.migration.internal.H2SchemaRenderer;
import com.holo.framework.horm.migration.internal.MySQLSchemaRenderer;
import com.holo.framework.horm.migration.internal.TableDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema 渲染器单元测试。
 */
class H2SchemaRendererTest {

    private final H2SchemaRenderer renderer = new H2SchemaRenderer();

    @Test
    void renderCreateTableSimple() {
        TableDefinition table = new TableDefinition("users");
        DefaultTableBuilder builder = new DefaultTableBuilder(table);
        builder.bigIncrements("id");
        builder.string("email", 128).notNull().unique();

        String ddl = renderer.renderCreateTable(table);

        assertThat(ddl).contains("CREATE TABLE users");
        assertThat(ddl).contains("id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY");
        assertThat(ddl).contains("email VARCHAR(128) NOT NULL UNIQUE");
    }

    @Test
    void renderCreateTableWithTimestamps() {
        TableDefinition table = new TableDefinition("posts");
        DefaultTableBuilder builder = new DefaultTableBuilder(table);
        builder.bigIncrements("id");
        builder.string("title", 255).notNull();
        builder.timestamps();

        String ddl = renderer.renderCreateTable(table);

        assertThat(ddl).contains("created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
        assertThat(ddl).contains("updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
    }

    @Test
    void renderCreateTableWithForeignKey() {
        TableDefinition table = new TableDefinition("orders");
        DefaultTableBuilder builder = new DefaultTableBuilder(table);
        builder.bigIncrements("id");
        builder.bigInteger("user_id").notNull();
        table.addForeignKey(new com.holo.framework.horm.migration.internal.ForeignKeyDefinition(
            "user_id", "users", "id"));

        String ddl = renderer.renderCreateTable(table);

        assertThat(ddl).contains("FOREIGN KEY (user_id) REFERENCES users(id)");
    }

    @Test
    void renderAddColumn() {
        ColumnDefinition col = new ColumnDefinition("phone", "VARCHAR");
        col.setLength(20);
        col.setNullable(true);

        String ddl = renderer.renderAddColumn("users", col);

        assertThat(ddl).isEqualTo("ALTER TABLE users ADD COLUMN phone VARCHAR(20)");
    }

    @Test
    void renderDropColumn() {
        String ddl = renderer.renderDropColumn("users", "phone");

        assertThat(ddl).isEqualTo("ALTER TABLE users DROP COLUMN phone");
    }

    @Test
    void renderCreateIndex() {
        String ddl = renderer.renderCreateIndex("idx_users_email", "users", "email");

        assertThat(ddl).isEqualTo("CREATE INDEX idx_users_email ON users (email)");
    }

    @Test
    void renderDropTable() {
        String ddl = renderer.renderDropTable("users");

        assertThat(ddl).isEqualTo("DROP TABLE users");
    }

    @Test
    void renderDecimalColumn() {
        TableDefinition table = new TableDefinition("products");
        DefaultTableBuilder builder = new DefaultTableBuilder(table);
        builder.decimal("price", 10, 2).notNull();

        String ddl = renderer.renderCreateTable(table);

        assertThat(ddl).contains("price DECIMAL(10,2) NOT NULL");
    }
}
