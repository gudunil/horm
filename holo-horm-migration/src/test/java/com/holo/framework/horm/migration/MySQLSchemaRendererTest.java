package com.holo.framework.horm.migration;

import com.holo.framework.horm.migration.internal.ColumnDefinition;
import com.holo.framework.horm.migration.internal.DefaultTableBuilder;
import com.holo.framework.horm.migration.internal.MySQLSchemaRenderer;
import com.holo.framework.horm.migration.internal.TableDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL Schema 渲染器单元测试。
 */
class MySQLSchemaRendererTest {

    private final MySQLSchemaRenderer renderer = new MySQLSchemaRenderer();

    @Test
    void renderCreateTableWithEngine() {
        TableDefinition table = new TableDefinition("users");
        DefaultTableBuilder builder = new DefaultTableBuilder(table);
        builder.bigIncrements("id");
        builder.string("email", 128).notNull();

        String ddl = renderer.renderCreateTable(table);

        assertThat(ddl).contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @Test
    void renderCreateTableWithComment() {
        TableDefinition table = new TableDefinition("users");
        DefaultTableBuilder builder = new DefaultTableBuilder(table);
        builder.bigIncrements("id");
        builder.string("email", 128).notNull().comment("用户邮箱");

        String ddl = renderer.renderCreateTable(table);

        assertThat(ddl).contains("COMMENT '用户邮箱'");
    }

    @Test
    void renderRenameTable() {
        String ddl = renderer.renderRenameTable("users", "user_accounts");

        assertThat(ddl).isEqualTo("RENAME TABLE users TO user_accounts");
    }

    @Test
    void renderRenameColumn() {
        String ddl = renderer.renderRenameColumn("users", "email", "email_address");

        assertThat(ddl).isEqualTo("ALTER TABLE users CHANGE COLUMN email email_address");
    }

    @Test
    void renderDropIndex() {
        String ddl = renderer.renderDropIndex("idx_users_email", "users");

        assertThat(ddl).isEqualTo("DROP INDEX idx_users_email ON users");
    }

    @Test
    void renderModifyColumn() {
        ColumnDefinition col = new ColumnDefinition("email", "VARCHAR");
        col.setLength(256);
        col.setNullable(false);

        String ddl = renderer.renderModifyColumn("users", col);

        assertThat(ddl).isEqualTo("ALTER TABLE users MODIFY COLUMN email VARCHAR(256) NOT NULL");
    }
}
