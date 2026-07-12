package com.holo.framework.horm.migration.internal;

import java.util.Objects;

/**
 * 外键定义数据对象。
 */
public final class ForeignKeyDefinition {

    private final String columnName;
    private final String referencedTable;
    private final String referencedColumn;

    public ForeignKeyDefinition(String columnName, String referencedTable, String referencedColumn) {
        this.columnName = Objects.requireNonNull(columnName, "columnName must not be null");
        this.referencedTable = Objects.requireNonNull(referencedTable, "referencedTable must not be null");
        this.referencedColumn = Objects.requireNonNull(referencedColumn, "referencedColumn must not be null");
    }

    public String getColumnName() {
        return columnName;
    }

    public String getReferencedTable() {
        return referencedTable;
    }

    public String getReferencedColumn() {
        return referencedColumn;
    }
}
