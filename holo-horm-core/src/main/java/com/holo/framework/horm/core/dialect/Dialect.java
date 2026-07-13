package com.holo.framework.horm.core.dialect;

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
}
