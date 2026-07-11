package com.holo.framework.horm.core.dialect;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresDialectTest {

    private final PostgresDialect dialect = new PostgresDialect();

    @Test
    void name() {
        assertThat(dialect.name()).isEqualTo("postgresql");
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
        assertThat(dialect.identityColumn()).isEqualTo("BIGSERIAL");
    }

    @Test
    void identityStrategy() {
        assertThat(dialect.identityStrategy()).isEqualTo(IdentityStrategy.SERIAL);
    }

    @Test
    void identifierQuoteChar() {
        assertThat(dialect.identifierQuoteChar()).isEqualTo('"');
    }

    @Test
    void quoteIdentifier() {
        assertThat(dialect.quoteIdentifier("order")).isEqualTo("\"order\"");
    }

    @Test
    void sqlTypeString() {
        assertThat(dialect.sqlType(String.class)).isEqualTo("VARCHAR(255)");
    }

    @Test
    void sqlTypeInteger() {
        assertThat(dialect.sqlType(Integer.class)).isEqualTo("INTEGER");
    }

    @Test
    void sqlTypeIntPrimitive() {
        assertThat(dialect.sqlType(int.class)).isEqualTo("INTEGER");
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
        assertThat(dialect.sqlType(BigDecimal.class)).isEqualTo("NUMERIC(19,2)");
    }

    @Test
    void sqlTypeBoolean() {
        assertThat(dialect.sqlType(Boolean.class)).isEqualTo("BOOLEAN");
    }

    @Test
    void sqlTypeBooleanPrimitive() {
        assertThat(dialect.sqlType(boolean.class)).isEqualTo("BOOLEAN");
    }

    @Test
    void sqlTypeInstant() {
        assertThat(dialect.sqlType(Instant.class)).isEqualTo("TIMESTAMP");
    }

    @Test
    void sqlTypeByteArray() {
        assertThat(dialect.sqlType(byte[].class)).isEqualTo("BYTEA");
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
            "INSERT INTO \"user\" (\"id\", \"name\", \"email\") VALUES (?, ?, ?)"
                + " ON CONFLICT (\"id\") DO UPDATE SET \"name\"=EXCLUDED.\"name\", \"email\"=EXCLUDED.\"email\"");
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

    @Test
    void batchInsertSyntax() {
        assertThat(dialect.batchInsertSyntax()).isEqualTo(BatchInsertSyntax.VALUES_LIST);
    }
}
