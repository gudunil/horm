package com.holo.framework.horm.core.dialect;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class H2DialectTest {

    @Nested
    class MysqlMode {

        private final H2Dialect dialect = new H2Dialect("mysql");

        @Test
        void name() {
            assertThat(dialect.name()).isEqualTo("h2");
        }

        @Test
        void paginateWithOffset() {
            var sql = new StringBuilder("SELECT * FROM t");
            var bindings = new ArrayList<Object>();
            dialect.paginate(sql, bindings, 20, 10);
            assertThat(sql.toString()).isEqualTo("SELECT * FROM t LIMIT ? OFFSET ?");
            assertThat(bindings).containsExactly(10L, 20L);
        }

        @Test
        void paginateWithoutOffset() {
            var sql = new StringBuilder("SELECT * FROM t");
            var bindings = new ArrayList<Object>();
            dialect.paginate(sql, bindings, 0, 10);
            assertThat(sql.toString()).isEqualTo("SELECT * FROM t LIMIT ?");
            assertThat(bindings).containsExactly(10L);
        }

        @Test
        void identityColumn() {
            assertThat(dialect.identityColumn()).isEqualTo("BIGINT AUTO_INCREMENT");
        }

        @Test
        void identityStrategy() {
            assertThat(dialect.identityStrategy()).isEqualTo(IdentityStrategy.IDENTITY);
        }

        @Test
        void identifierQuoteChar() {
            assertThat(dialect.identifierQuoteChar()).isEqualTo('`');
        }

        @Test
        void quoteIdentifier() {
            assertThat(dialect.quoteIdentifier("user")).isEqualTo("`user`");
        }

        @Test
        void sqlTypeString() {
            assertThat(dialect.sqlType(String.class)).isEqualTo("VARCHAR(255)");
        }

        @Test
        void sqlTypeInteger() {
            assertThat(dialect.sqlType(Integer.class)).isEqualTo("INT");
        }

        @Test
        void sqlTypeLong() {
            assertThat(dialect.sqlType(Long.class)).isEqualTo("BIGINT");
        }

        @Test
        void sqlTypeBigDecimal() {
            assertThat(dialect.sqlType(BigDecimal.class)).isEqualTo("DECIMAL(19,2)");
        }

        @Test
        void sqlTypeBoolean() {
            assertThat(dialect.sqlType(Boolean.class)).isEqualTo("TINYINT(1)");
        }

        @Test
        void sqlTypeInstant() {
            assertThat(dialect.sqlType(Instant.class)).isEqualTo("DATETIME(6)");
        }

        @Test
        void sqlTypeByteArray() {
            assertThat(dialect.sqlType(byte[].class)).isEqualTo("BLOB");
        }

        @Test
        void sqlTypeUnknown() {
            assertThat(dialect.sqlType(Object.class)).isEqualTo("VARCHAR(255)");
        }

        @Test
        void supportsUpsert() {
            assertThat(dialect.supportsUpsert()).isTrue();
        }

        @Test
        void upsert() {
            var result = dialect.upsert("user",
                new String[]{"id", "name"},
                new String[]{"id"},
                new String[]{"name"});
            assertThat(result).isEqualTo(
                "INSERT INTO `user` (`id`, `name`) VALUES (?, ?)"
                    + " ON DUPLICATE KEY UPDATE `name`=VALUES(`name`)");
        }

        @Test
        void supportsBatchInsertValues() {
            assertThat(dialect.supportsBatchInsertValues()).isTrue();
        }

        @Test
        void dropIndex() {
            assertThat(dialect.dropIndex("idx_name", "user"))
                .isEqualTo("DROP INDEX `idx_name` ON `user`");
        }

        @Test
        void renameTable() {
            assertThat(dialect.renameTable("old_table", "new_table"))
                .isEqualTo("RENAME TABLE `old_table` TO `new_table`");
        }

        @Test
        void booleanType() {
            assertThat(dialect.booleanType()).isEqualTo("TINYINT(1)");
        }

        @Test
        void timestampType() {
            assertThat(dialect.timestampType()).isEqualTo("DATETIME(6)");
        }

        @Test
        void batchInsertSyntax() {
            assertThat(dialect.batchInsertSyntax()).isEqualTo(BatchInsertSyntax.VALUES_LIST);
        }
    }

    @Nested
    class PostgresqlMode {

        private final H2Dialect dialect = new H2Dialect("postgresql");

        @Test
        void name() {
            assertThat(dialect.name()).isEqualTo("h2-postgresql");
        }

        @Test
        void paginateWithOffset() {
            var sql = new StringBuilder("SELECT * FROM t");
            var bindings = new ArrayList<Object>();
            dialect.paginate(sql, bindings, 30, 15);
            assertThat(sql.toString()).isEqualTo("SELECT * FROM t LIMIT ? OFFSET ?");
            assertThat(bindings).containsExactly(15L, 30L);
        }

        @Test
        void paginateWithoutOffset() {
            var sql = new StringBuilder("SELECT * FROM t");
            var bindings = new ArrayList<Object>();
            dialect.paginate(sql, bindings, 0, 15);
            assertThat(sql.toString()).isEqualTo("SELECT * FROM t LIMIT ?");
            assertThat(bindings).containsExactly(15L);
        }

        @Test
        void identityColumn() {
            assertThat(dialect.identityColumn()).isEqualTo("BIGINT GENERATED BY DEFAULT AS IDENTITY");
        }

        @Test
        void identityStrategy() {
            assertThat(dialect.identityStrategy()).isEqualTo(IdentityStrategy.IDENTITY);
        }

        @Test
        void identifierQuoteChar() {
            assertThat(dialect.identifierQuoteChar()).isEqualTo('"');
        }

        @Test
        void quoteIdentifier() {
            assertThat(dialect.quoteIdentifier("user")).isEqualTo("\"user\"");
        }

        @Test
        void sqlTypeString() {
            assertThat(dialect.sqlType(String.class)).isEqualTo("VARCHAR(255)");
        }

        @Test
        void sqlTypeInteger() {
            assertThat(dialect.sqlType(Integer.class)).isEqualTo("INT");
        }

        @Test
        void sqlTypeLong() {
            assertThat(dialect.sqlType(Long.class)).isEqualTo("BIGINT");
        }

        @Test
        void sqlTypeBigDecimal() {
            assertThat(dialect.sqlType(BigDecimal.class)).isEqualTo("DECIMAL(19,2)");
        }

        @Test
        void sqlTypeBoolean() {
            assertThat(dialect.sqlType(Boolean.class)).isEqualTo("BOOLEAN");
        }

        @Test
        void sqlTypeInstant() {
            assertThat(dialect.sqlType(Instant.class)).isEqualTo("TIMESTAMP");
        }

        @Test
        void sqlTypeByteArray() {
            assertThat(dialect.sqlType(byte[].class)).isEqualTo("BLOB");
        }

        @Test
        void sqlTypeUnknown() {
            assertThat(dialect.sqlType(Object.class)).isEqualTo("VARCHAR(255)");
        }

        @Test
        void supportsUpsert() {
            assertThat(dialect.supportsUpsert()).isTrue();
        }

        @Test
        void upsert() {
            var result = dialect.upsert("user",
                new String[]{"id", "name"},
                new String[]{"id"},
                new String[]{"name"});
            assertThat(result).isEqualTo(
                "INSERT INTO \"user\" (\"id\", \"name\") VALUES (?, ?)"
                    + " ON CONFLICT (\"id\") DO UPDATE SET \"name\"=EXCLUDED.\"name\"");
        }

        @Test
        void supportsBatchInsertValues() {
            assertThat(dialect.supportsBatchInsertValues()).isTrue();
        }

        @Test
        void dropIndex() {
            assertThat(dialect.dropIndex("idx_name", "user"))
                .isEqualTo("DROP INDEX \"idx_name\"");
        }

        @Test
        void renameTable() {
            assertThat(dialect.renameTable("old_table", "new_table"))
                .isEqualTo("ALTER TABLE \"old_table\" RENAME TO \"new_table\"");
        }

        @Test
        void booleanType() {
            assertThat(dialect.booleanType()).isEqualTo("BOOLEAN");
        }

        @Test
        void timestampType() {
            assertThat(dialect.timestampType()).isEqualTo("TIMESTAMP");
        }
    }

    @Test
    void nullModeThrows() {
        assertThatThrownBy(() -> new H2Dialect(null))
            .isInstanceOf(NullPointerException.class);
    }
}
