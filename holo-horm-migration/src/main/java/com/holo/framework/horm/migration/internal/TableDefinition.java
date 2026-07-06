package com.holo.framework.horm.migration.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 表定义数据对象，存储表的所有列和外键约束。
 */
public final class TableDefinition {

    private final String name;
    private final List<ColumnDefinition> columns = new ArrayList<>();
    private final List<ForeignKeyDefinition> foreignKeys = new ArrayList<>();

    public TableDefinition(String name) {
        this.name = Objects.requireNonNull(name, "table name must not be null");
    }

    public String getName() {
        return name;
    }

    public void addColumn(ColumnDefinition column) {
        columns.add(column);
    }

    public List<ColumnDefinition> getColumns() {
        return columns;
    }

    public void addForeignKey(ForeignKeyDefinition foreignKey) {
        foreignKeys.add(foreignKey);
    }

    public List<ForeignKeyDefinition> getForeignKeys() {
        return foreignKeys;
    }
}
