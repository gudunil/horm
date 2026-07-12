package com.holo.framework.horm.core.dialect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DialectTest {

    @Test
    void quoteIdentifierWrapsWithQuoteChar() {
        Dialect dialect = new Dialect() {
            @Override public String name() { return "test"; }
            @Override public void paginate(StringBuilder sql, java.util.List<Object> bindings, long offset, long limit) {}
            @Override public String identityColumn() { return ""; }
            @Override public IdentityStrategy identityStrategy() { return IdentityStrategy.AUTO_INCREMENT; }
            @Override public char identifierQuoteChar() { return '"'; }
            @Override public String sqlType(Class<?> javaType) { return ""; }
            @Override public boolean supportsUpsert() { return false; }
            @Override public String upsert(String table, String[] columns, String[] uniqueColumns, String[] updateColumns) { return ""; }
            @Override public boolean supportsBatchInsertValues() { return false; }
            @Override public String dropIndex(String indexName, String tableName) { return ""; }
            @Override public String renameTable(String oldName, String newName) { return ""; }
            @Override public String booleanType() { return ""; }
            @Override public String timestampType() { return ""; }
        };

        assertThat(dialect.quoteIdentifier("user")).isEqualTo("\"user\"");
    }

    @Test
    void quoteIdentifierUsesBacktick() {
        Dialect dialect = new Dialect() {
            @Override public String name() { return "test"; }
            @Override public void paginate(StringBuilder sql, java.util.List<Object> bindings, long offset, long limit) {}
            @Override public String identityColumn() { return ""; }
            @Override public IdentityStrategy identityStrategy() { return IdentityStrategy.AUTO_INCREMENT; }
            @Override public char identifierQuoteChar() { return '`'; }
            @Override public String sqlType(Class<?> javaType) { return ""; }
            @Override public boolean supportsUpsert() { return false; }
            @Override public String upsert(String table, String[] columns, String[] uniqueColumns, String[] updateColumns) { return ""; }
            @Override public boolean supportsBatchInsertValues() { return false; }
            @Override public String dropIndex(String indexName, String tableName) { return ""; }
            @Override public String renameTable(String oldName, String newName) { return ""; }
            @Override public String booleanType() { return ""; }
            @Override public String timestampType() { return ""; }
        };

        assertThat(dialect.quoteIdentifier("order")).isEqualTo("`order`");
    }

    @Test
    void defaultBatchInsertSyntaxIsValuesList() {
        Dialect dialect = new Dialect() {
            @Override public String name() { return "test"; }
            @Override public void paginate(StringBuilder sql, java.util.List<Object> bindings, long offset, long limit) {}
            @Override public String identityColumn() { return ""; }
            @Override public IdentityStrategy identityStrategy() { return IdentityStrategy.AUTO_INCREMENT; }
            @Override public char identifierQuoteChar() { return '`'; }
            @Override public String sqlType(Class<?> javaType) { return ""; }
            @Override public boolean supportsUpsert() { return false; }
            @Override public String upsert(String table, String[] columns, String[] uniqueColumns, String[] updateColumns) { return ""; }
            @Override public boolean supportsBatchInsertValues() { return false; }
            @Override public String dropIndex(String indexName, String tableName) { return ""; }
            @Override public String renameTable(String oldName, String newName) { return ""; }
            @Override public String booleanType() { return ""; }
            @Override public String timestampType() { return ""; }
        };

        assertThat(dialect.batchInsertSyntax()).isEqualTo(BatchInsertSyntax.VALUES_LIST);
    }
}
