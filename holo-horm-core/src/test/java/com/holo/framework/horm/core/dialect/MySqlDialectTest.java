package com.holo.framework.horm.core.dialect;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlDialectTest {

    private final MySqlDialect dialect = new MySqlDialect();

    @Test
    void name() {
        assertThat(dialect.name()).isEqualTo("mysql");
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
    void paginateZeroOffsetOmitsOffset() {
        var sql = new StringBuilder("SELECT * FROM t");
        var bindings = new ArrayList<Object>();
        dialect.paginate(sql, bindings, 0, 5);
        assertThat(sql.toString()).doesNotContain("OFFSET");
        assertThat(bindings).containsExactly(5L);
    }

    @Test
    void identityColumn() {
        assertThat(dialect.identityColumn()).isEqualTo("BIGINT AUTO_INCREMENT");
    }

    @Test
    void identityStrategy() {
        assertThat(dialect.identityStrategy()).isEqualTo(IdentityStrategy.AUTO_INCREMENT);
    }

    @Test
    void identifierQuoteChar() {
        assertThat(dialect.identifierQuoteChar()).isEqualTo('`');
    }

    @Test
    void quoteIdentifier() {
        assertThat(dialect.quoteIdentifier("order")).isEqualTo("`order`");
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
    void sqlTypeIntPrimitive() {
        assertThat(dialect.sqlType(int.class)).isEqualTo("INT");
    }

    @Test
    void sqlTypeLong() {
        assertThat(dialect.sqlType(Long.class)).isEqualTo("BIGINT");
    }

    @Test
    void sqlTypeLongPrimitive() {
        assertThat(dialect.sqlType(long.class)).isEqualTo("BIGINT");
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
    void sqlTypeBooleanPrimitive() {
        assertThat(dialect.sqlType(boolean.class)).isEqualTo("TINYINT(1)");
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
    void sqlTypeUnknownReturnsVarchar() {
        assertThat(dialect.sqlType(Object.class)).isEqualTo("VARCHAR(255)");
    }

    @Test
    void supportsUpsert() {
        assertThat(dialect.supportsUpsert()).isTrue();
    }

    @Test
    void upsert() {
        var result = dialect.upsert("user",
            new String[]{"id", "name", "email"},
            new String[]{"id"},
            new String[]{"name", "email"});
        assertThat(result).isEqualTo(
            "INSERT INTO `user` (`id`, `name`, `email`) VALUES (?, ?, ?) AS new_row"
                + " ON DUPLICATE KEY UPDATE `name`=new_row.`name`, `email`=new_row.`email`");
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
