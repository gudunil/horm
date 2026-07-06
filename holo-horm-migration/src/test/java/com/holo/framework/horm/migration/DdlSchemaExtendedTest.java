package com.holo.framework.horm.migration;

import com.holo.framework.horm.migration.internal.DdlSchema;
import com.holo.framework.horm.migration.internal.H2SchemaRenderer;
import com.holo.framework.horm.migration.internal.MySQLSchemaRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DDL Schema 扩展单元测试（MySQL + 更多边缘场景）。
 */
class DdlSchemaExtendedTest {

    @Test
    void createTableWithMySQLRenderer() {
        DdlSchema schema = new DdlSchema(new MySQLSchemaRenderer());
        schema.createTable("products", t -> {
            t.bigIncrements("id");
            t.string("name", 255).notNull();
        });

        List<String> statements = schema.getStatements();
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @Test
    void alterTableAddColumnWithMultipleColumns() {
        DdlSchema schema = new DdlSchema(new H2SchemaRenderer());
        schema.alterTable("users", t -> {
            t.string("phone", 20).nullable();
            t.integer("age").defaultVal("0");
        });

        List<String> statements = schema.getStatements();
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("ADD COLUMN phone VARCHAR(20)");
        assertThat(statements.get(1)).contains("ADD COLUMN age INT DEFAULT 0");
    }

    @Test
    void alterTableModifyColumn() {
        DdlSchema schema = new DdlSchema(new H2SchemaRenderer());
        schema.alterTable("users", t -> {
            t.modifyColumn("email").notNull();
        });

        List<String> statements = schema.getStatements();
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).contains("ALTER TABLE users ALTER COLUMN email");
    }

    @Test
    void alterTableMultipleOperations() {
        DdlSchema schema = new DdlSchema(new H2SchemaRenderer());
        schema.alterTable("users", t -> {
            t.dropColumn("obsolete_field");
            t.string("new_field", 100).notNull().index();
        });

        List<String> statements = schema.getStatements();
        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).contains("DROP COLUMN obsolete_field");
        assertThat(statements.get(1)).contains("ADD COLUMN new_field VARCHAR(100) NOT NULL");
        assertThat(statements.get(2)).contains("CREATE INDEX idx_users_new_field");
    }

    @Test
    void renameTableAndColumn() {
        DdlSchema schema = new DdlSchema(new H2SchemaRenderer());
        schema.renameTable("old_users", "new_users");
        schema.renameColumn("new_users", "email", "email_address");

        List<String> statements = schema.getStatements();
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("RENAME TO");
        assertThat(statements.get(1)).contains("RENAME TO");
    }

    @Test
    void dropIndex() {
        DdlSchema schema = new DdlSchema(new H2SchemaRenderer());
        schema.dropIndex("idx_users_email", "users");

        List<String> statements = schema.getStatements();
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).isEqualTo("DROP INDEX idx_users_email");
    }
}