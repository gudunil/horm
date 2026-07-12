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
}
