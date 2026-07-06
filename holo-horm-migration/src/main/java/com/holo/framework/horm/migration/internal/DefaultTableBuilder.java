package com.holo.framework.horm.migration.internal;

import com.holo.framework.horm.migration.ColumnBuilder;
import com.holo.framework.horm.migration.TableBuilder;

/**
 * TableBuilder 默认实现，将链式调用转换为 TableDefinition。
 */
public final class DefaultTableBuilder implements TableBuilder {

    private final TableDefinition tableDefinition;

    public DefaultTableBuilder(TableDefinition tableDefinition) {
        this.tableDefinition = tableDefinition;
    }

    @Override
    public ColumnBuilder bigIncrements(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "BIGINT");
        col.setPrimaryKey(true);
        col.setAutoIncrement(true);
        col.setNullable(false);
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public ColumnBuilder increments(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "INT");
        col.setPrimaryKey(true);
        col.setAutoIncrement(true);
        col.setNullable(false);
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public ColumnBuilder bigInteger(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "BIGINT");
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public ColumnBuilder integer(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "INT");
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public ColumnBuilder smallInteger(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "SMALLINT");
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public ColumnBuilder tinyInteger(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "TINYINT");
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public ColumnBuilder string(String columnName, int length) {
        ColumnDefinition col = new ColumnDefinition(columnName, "VARCHAR");
        col.setLength(length);
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public ColumnBuilder text(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "TEXT");
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public ColumnBuilder decimal(String columnName, int precision, int scale) {
        ColumnDefinition col = new ColumnDefinition(columnName, "DECIMAL");
        col.setPrecision(precision);
        col.setScale(scale);
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public ColumnBuilder float_(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "FLOAT");
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public ColumnBuilder double_(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "DOUBLE");
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public ColumnBuilder boolean_(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "BOOLEAN");
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public ColumnBuilder date(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "DATE");
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public ColumnBuilder time(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "TIME");
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public ColumnBuilder datetime(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "DATETIME");
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public ColumnBuilder timestamp(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "TIMESTAMP");
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    @Override
    public void timestamps() {
        ColumnDefinition createdAt = new ColumnDefinition("created_at", "DATETIME");
        createdAt.setNullable(false);
        createdAt.setDefaultValue("CURRENT_TIMESTAMP");
        tableDefinition.addColumn(createdAt);

        ColumnDefinition updatedAt = new ColumnDefinition("updated_at", "DATETIME");
        updatedAt.setNullable(false);
        updatedAt.setDefaultValue("CURRENT_TIMESTAMP");
        tableDefinition.addColumn(updatedAt);
    }

    @Override
    public TableBuilder foreignKey(String columnName, String referencedTable, String referencedColumn) {
        tableDefinition.addForeignKey(new ForeignKeyDefinition(columnName, referencedTable, referencedColumn));
        return this;
    }

    @Override
    public TableBuilder dropColumn(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "DROP");
        tableDefinition.addColumn(col);
        return this;
    }

    @Override
    public ColumnBuilder modifyColumn(String columnName) {
        ColumnDefinition col = new ColumnDefinition(columnName, "MODIFY");
        tableDefinition.addColumn(col);
        return new DefaultColumnBuilder(col);
    }

    public TableDefinition getTableDefinition() {
        return tableDefinition;
    }
}
