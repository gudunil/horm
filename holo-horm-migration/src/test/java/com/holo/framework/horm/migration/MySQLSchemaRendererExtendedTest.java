package com.holo.framework.horm.migration;

import com.holo.framework.horm.migration.internal.MySQLSchemaRenderer;
import com.holo.framework.horm.migration.internal.TableDefinition;
import com.holo.framework.horm.migration.internal.DefaultTableBuilder;
import com.holo.framework.horm.migration.internal.ColumnDefinition;
import com.holo.framework.horm.migration.internal.ForeignKeyDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MySQLSchemaRenderer} 扩展单元测试。
 */
class MySQLSchemaRendererExtendedTest {

    private final MySQLSchemaRenderer renderer = new MySQLSchemaRenderer();

    @Test
    void renderCreateTableWithEngineAndCharset() {
        TableDefinition table = new TableDefinition("users");
        DefaultTableBuilder builder = new DefaultTableBuilder(table);
        builder.bigIncrements("id");

        String ddl = renderer.renderCreateTable(table);

        assertThat(ddl).contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @Test
    void renderModifyColumn() {
        ColumnDefinition col = new ColumnDefinition("email", "VARCHAR");
        col.setLength(255);
        col.setNullable(false);

        String ddl = renderer.renderModifyColumn("users", col);

        assertThat(ddl).isEqualTo("ALTER TABLE users MODIFY COLUMN email VARCHAR(255) NOT NULL");
    }

    @Test
    void renderDropIndexWithTableName() {
        String ddl = renderer.renderDropIndex("idx_users_email", "users");

        assertThat(ddl).isEqualTo("DROP INDEX idx_users_email ON users");
    }

    @Test
    void renderRenameTable() {
        String ddl = renderer.renderRenameTable("old_users", "new_users");

        assertThat(ddl).isEqualTo("RENAME TABLE old_users TO new_users");
    }

    @Test
    void renderRenameColumn() {
        String ddl = renderer.renderRenameColumn("users", "email", "email_address");

        assertThat(ddl).isEqualTo("ALTER TABLE users CHANGE COLUMN email email_address");
    }

    @Test
    void renderForeignKeyWithConstraint() {
        ForeignKeyDefinition fk = new ForeignKeyDefinition("user_id", "users", "id");
        String ddl = renderer.renderForeignKey(fk);

        assertThat(ddl).isEqualTo("CONSTRAINT fk_user_id FOREIGN KEY (user_id) REFERENCES users(id)");
    }

    @Test
    void renderColumnDefinitionWithComment() {
        ColumnDefinition col = new ColumnDefinition("status", "VARCHAR");
        col.setLength(16);
        col.setDefaultValue("'active'");
        col.setComment("用户状态");

        String ddl = renderer.renderColumnDefinition(col);

        assertThat(ddl).contains("COMMENT '用户状态'");
    }

    @Test
    void renderDropTable() {
        String ddl = renderer.renderDropTable("users");
        assertThat(ddl).isEqualTo("DROP TABLE users");
    }

    @Test
    void renderAddColumn() {
        ColumnDefinition col = new ColumnDefinition("phone", "VARCHAR");
        col.setLength(20);

        String ddl = renderer.renderAddColumn("users", col);

        assertThat(ddl).isEqualTo("ALTER TABLE users ADD COLUMN phone VARCHAR(20)");
    }
}