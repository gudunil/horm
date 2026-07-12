package com.holo.framework.horm.migration.internal;

import com.holo.framework.horm.migration.ColumnBuilder;

/**
 * ColumnBuilder 默认实现，将链式调用转换为 ColumnDefinition。
 */
public final class DefaultColumnBuilder implements ColumnBuilder {

    private final ColumnDefinition definition;

    public DefaultColumnBuilder(ColumnDefinition definition) {
        this.definition = definition;
    }

    @Override
    public ColumnBuilder notNull() {
        definition.setNullable(false);
        return this;
    }

    @Override
    public ColumnBuilder nullable() {
        definition.setNullable(true);
        return this;
    }

    @Override
    public ColumnBuilder unique() {
        definition.setUnique(true);
        return this;
    }

    @Override
    public ColumnBuilder defaultVal(String value) {
        definition.setDefaultValue(value);
        return this;
    }

    @Override
    public ColumnBuilder primaryKey() {
        definition.setPrimaryKey(true);
        definition.setNullable(false);
        return this;
    }

    @Override
    public ColumnBuilder autoIncrement() {
        definition.setAutoIncrement(true);
        return this;
    }

    @Override
    public ColumnBuilder index() {
        definition.setCreateIndex(true);
        return this;
    }

    @Override
    public ColumnBuilder references(String referencedTable, String referencedColumn) {
        definition.setReferencedTable(referencedTable);
        definition.setReferencedColumn(referencedColumn);
        return this;
    }

    @Override
    public ColumnBuilder comment(String comment) {
        definition.setComment(comment);
        return this;
    }

    public ColumnDefinition getDefinition() {
        return definition;
    }
}
