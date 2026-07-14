package com.holo.framework.horm.core.dialect;

import com.holo.framework.horm.meta.query.expr.FunctionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface Dialect {

    String name();

    void paginate(StringBuilder sql, List<Object> bindings, long offset, long limit);

    String identityColumn();

    IdentityStrategy identityStrategy();

    char identifierQuoteChar();

    default String quoteIdentifier(String identifier) {
        char q = identifierQuoteChar();
        return q + identifier + q;
    }

    String sqlType(Class<?> javaType);

    boolean supportsUpsert();

    String upsert(String table, String[] columns, String[] uniqueColumns, String[] updateColumns);

    boolean supportsBatchInsertValues();

    String dropIndex(String indexName, String tableName);

    String renameTable(String oldName, String newName);

    String booleanType();

    String timestampType();

    default BatchInsertSyntax batchInsertSyntax() {
        return BatchInsertSyntax.VALUES_LIST;
    }

    /**
     * Whether this dialect guarantees that {@link java.sql.PreparedStatement#getGeneratedKeys()}
     * returns generated keys in the same order as the rows added via
     * {@link java.sql.PreparedStatement#addBatch()}. Defaults to {@code true}.
     *
     * <p>When {@code false}, {@code JdbcOperations.batchInsert} refuses to
     * perform batch inserts with generated keys to avoid silently assigning
     * wrong primary keys to entities.
     */
    default boolean supportsBatchInsertGeneratedKeysInOrder() {
        return true;
    }

    /**
     * Render a function call for this dialect.
     *
     * @param type function type (never {@link FunctionType#RAW} — RAW expressions
     *        are rendered directly via {@code Expr.sqlFragment()})
     * @param argSqlFragments pre-rendered argument SQL fragments (without function name);
     *        for {@code COUNT(*)}, this list is empty
     * @return rendered function expression, e.g. "UPPER(t0.name)" or "DATE_FORMAT(?, ?)"
     * @throws IllegalArgumentException if type is {@code RAW}
     */
    default String functionSql(FunctionType type, List<String> argSqlFragments) {
        if (type == FunctionType.RAW) {
            throw new IllegalArgumentException("FunctionType.RAW must not reach Dialect.functionSql(); "
                + "RAW expressions are rendered directly via Expr.sqlFragment()");
        }
        if (type == FunctionType.COUNT && argSqlFragments.isEmpty()) {
            return "COUNT(*)";
        }
        return type.name() + "(" + String.join(", ", argSqlFragments) + ")";
    }

    /**
     * Whether this dialect supports the given function type.
     */
    default boolean supportsFunction(FunctionType type) {
        return true;
    }

    /**
     * Translate a Java date format pattern to this dialect's native pattern.
     * Called at expression construction time, before SQL rendering, so the
     * translated pattern flows into bindings as a normal parameter value
     * (not embedded in SQL text — prevents SQL injection).
     *
     * @param javaPattern Java date format, e.g. {@code "yyyy-MM-dd"}
     * @return dialect-specific pattern, e.g. {@code "%Y-%m-%d"} for MySQL
     */
    default String translateDateFormatPattern(String javaPattern) {
        return javaPattern;
    }
}
