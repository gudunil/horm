package com.holo.framework.horm.core.dialect;

import com.holo.framework.horm.meta.query.expr.FunctionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;

public class PostgresDialect implements Dialect {

    @Override
    public String name() {
        return "postgresql";
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
        return "BIGSERIAL";
    }

    @Override
    public IdentityStrategy identityStrategy() {
        return IdentityStrategy.SERIAL;
    }

    @Override
    public char identifierQuoteChar() {
        return '"';
    }

    @Override
    public String sqlType(Class<?> javaType) {
        if (javaType == String.class) return "VARCHAR(255)";
        if (javaType == Integer.class || javaType == int.class) return "INTEGER";
        if (javaType == Long.class || javaType == long.class) return "BIGINT";
        if (javaType == BigDecimal.class) return "NUMERIC(19,2)";
        if (javaType == Boolean.class || javaType == boolean.class) return "BOOLEAN";
        if (javaType == Instant.class) return "TIMESTAMP";
        if (javaType == byte[].class) return "BYTEA";
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

        var uniqueJoiner = new StringJoiner(", ");
        for (var col : uniqueColumns) uniqueJoiner.add(quoteIdentifier(col));
        var uniqueCols = uniqueJoiner.toString();

        var updateClause = new StringJoiner(", ");
        for (var col : updateColumns) {
            updateClause.add(quoteIdentifier(col) + "=EXCLUDED." + quoteIdentifier(col));
        }

        return "INSERT INTO " + quoteIdentifier(table)
            + " (" + insertCols + ") VALUES (" + placeholders + ")"
            + " ON CONFLICT (" + uniqueCols + ") DO UPDATE SET " + updateClause;
    }

    @Override
    public boolean supportsBatchInsertValues() {
        return true;
    }

    @Override
    public String dropIndex(String indexName, String tableName) {
        return "DROP INDEX " + quoteIdentifier(indexName);
    }

    @Override
    public String renameTable(String oldName, String newName) {
        return "ALTER TABLE " + quoteIdentifier(oldName) + " RENAME TO " + quoteIdentifier(newName);
    }

    @Override
    public String booleanType() {
        return "BOOLEAN";
    }

    @Override
    public String timestampType() {
        return "TIMESTAMP";
    }

    @Override
    public String functionSql(FunctionType type, List<String> args) {
        return switch (type) {
            case DATE_FORMAT -> "TO_CHAR(" + args.get(0) + ", ?)";
            case YEAR -> "EXTRACT(YEAR FROM " + args.get(0) + ")";
            case MONTH -> "EXTRACT(MONTH FROM " + args.get(0) + ")";
            case DAY -> "EXTRACT(DAY FROM " + args.get(0) + ")";
            case NOW -> "CURRENT_TIMESTAMP";
            case CONCAT -> String.join(" || ", args);
            default -> Dialect.super.functionSql(type, args);
        };
    }

    @Override
    public String translateDateFormatPattern(String javaPattern) {
        return javaPattern
            .replace("yyyy", "YYYY").replace("yy", "YY")
            .replace("MM", "MM").replace("dd", "DD")
            .replace("HH", "HH24").replace("mm", "MI")
            .replace("ss", "SS");
    }
}
