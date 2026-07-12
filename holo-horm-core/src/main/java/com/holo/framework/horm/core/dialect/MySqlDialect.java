package com.holo.framework.horm.core.dialect;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;

public class MySqlDialect implements Dialect {

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public void paginate(StringBuilder sql, List<Object> bindings, long offset, long limit) {
        sql.append(" LIMIT ?");
        bindings.add(limit);
        if (offset > 0) {
            sql.append(" OFFSET ?");
            bindings.add(offset);
        }
    }

    @Override
    public String identityColumn() {
        return "BIGINT AUTO_INCREMENT";
    }

    @Override
    public IdentityStrategy identityStrategy() {
        return IdentityStrategy.AUTO_INCREMENT;
    }

    @Override
    public char identifierQuoteChar() {
        return '`';
    }

    @Override
    public String sqlType(Class<?> javaType) {
        if (javaType == String.class) return "VARCHAR(255)";
        if (javaType == Integer.class || javaType == int.class) return "INT";
        if (javaType == Long.class || javaType == long.class) return "BIGINT";
        if (javaType == BigDecimal.class) return "DECIMAL(19,2)";
        if (javaType == Boolean.class || javaType == boolean.class) return "TINYINT(1)";
        if (javaType == Instant.class) return "DATETIME(6)";
        if (javaType == byte[].class) return "BLOB";
        return "VARCHAR(255)";
    }

    @Override
    public boolean supportsUpsert() {
        return true;
    }

    @Override
    public String upsert(String table, String[] columns, String[] uniqueColumns, String[] updateColumns) {
        var sj = new StringJoiner(", ");
        for (var col : columns) sj.add(quoteIdentifier(col));
        var insertCols = sj.toString();

        sj = new StringJoiner(", ");
        for (var ignored : columns) sj.add("?");
        var placeholders = sj.toString();

        var updateClause = new StringJoiner(", ");
        for (var col : updateColumns) {
            updateClause.add(quoteIdentifier(col) + "=new_row." + quoteIdentifier(col));
        }

        return "INSERT INTO " + quoteIdentifier(table)
            + " (" + insertCols + ") VALUES (" + placeholders + ") AS new_row"
            + " ON DUPLICATE KEY UPDATE " + updateClause;
    }

    @Override
    public boolean supportsBatchInsertValues() {
        return true;
    }

    @Override
    public String dropIndex(String indexName, String tableName) {
        return "DROP INDEX " + quoteIdentifier(indexName) + " ON " + quoteIdentifier(tableName);
    }

    @Override
    public String renameTable(String oldName, String newName) {
        return "RENAME TABLE " + quoteIdentifier(oldName) + " TO " + quoteIdentifier(newName);
    }

    @Override
    public String booleanType() {
        return "TINYINT(1)";
    }

    @Override
    public String timestampType() {
        return "DATETIME(6)";
    }
}
