package com.holo.framework.horm.migration;

import com.holo.framework.horm.migration.internal.DdlSchema;
import com.holo.framework.horm.migration.internal.H2SchemaRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DDL Schema 单元测试。
 */
class DdlSchemaTest {

    private final DdlSchema schema = new DdlSchema(new H2SchemaRenderer());

    @Test
    void createTableCollectsStatements() {
        schema.createTable("users", t -> {
            t.bigIncrements("id");
            t.string("email", 128).notNull().unique();
            t.timestamps();
        });

        List<String> statements = schema.getStatements();
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).contains("CREATE TABLE users");
    }

    @Test
    void createTableWithIndexCollectsMultipleStatements() {
        schema.createTable("users", t -> {
            t.bigIncrements("id");
            t.string("email", 128).notNull().index();
        });

        List<String> statements = schema.getStatements();
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("CREATE TABLE users");
        assertThat(statements.get(1)).contains("CREATE INDEX idx_users_email");
    }

    @Test
    void alterTableAddColumn() {
        schema.alterTable("users", t -> {
            t.string("phone", 20).nullable();
        });

        List<String> statements = schema.getStatements();
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).contains("ALTER TABLE users ADD COLUMN phone VARCHAR(20)");
    }

    @Test
    void alterTableDropColumn() {
        schema.alterTable("users", t -> {
            t.dropColumn("phone");
        });

        List<String> statements = schema.getStatements();
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).contains("ALTER TABLE users DROP COLUMN phone");
    }

    @Test
    void dropTable() {
        schema.dropTable("users");

        List<String> statements = schema.getStatements();
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).isEqualTo("DROP TABLE users");
    }

    @Test
    void createIndex() {
        schema.createIndex("idx_users_email", "users", "email");

        List<String> statements = schema.getStatements();
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).isEqualTo("CREATE INDEX idx_users_email ON users (email)");
    }

    @Test
    void toSqlJoinsStatements() {
        schema.createTable("users", t -> {
            t.bigIncrements("id");
        });
        schema.createIndex("idx_users_id", "users", "id");

        String sql = schema.toSql();
        assertThat(sql).contains("CREATE TABLE users");
        assertThat(sql).contains("CREATE INDEX idx_users_id");
        assertThat(sql).endsWith(";");
    }
}
