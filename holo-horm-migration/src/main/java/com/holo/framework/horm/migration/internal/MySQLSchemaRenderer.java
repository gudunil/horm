package com.holo.framework.horm.migration.internal;

import java.util.StringJoiner;

/**
 * MySQL 数据库 Schema 渲染器。
 */
public final class MySQLSchemaRenderer implements SchemaRenderer {

    @Override
    public String renderCreateTable(TableDefinition table) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ");
        sb.append(table.getName()).append(" (\n");

        StringJoiner joiner = new StringJoiner(",\n");
        for (ColumnDefinition col : table.getColumns()) {
            if (!"DROP".equals(col.getType()) && !"MODIFY".equals(col.getType())) {
                joiner.add("    " + renderColumnDefinition(col));
            }
        }

        for (ForeignKeyDefinition fk : table.getForeignKeys()) {
            joiner.add("    " + renderForeignKey(fk));
        }

        sb.append(joiner.toString());
        sb.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        return sb.toString();
    }

    @Override
    public String renderAddColumn(String tableName, ColumnDefinition column) {
        return "ALTER TABLE " + tableName + " ADD COLUMN " + renderColumnDefinition(column);
    }

    @Override
    public String renderDropColumn(String tableName, String columnName) {
        return "ALTER TABLE " + tableName + " DROP COLUMN " + columnName;
    }

    @Override
    public String renderModifyColumn(String tableName, ColumnDefinition column) {
        return "ALTER TABLE " + tableName + " MODIFY COLUMN " + renderColumnDefinition(column);
    }

    @Override
    public String renderDropTable(String tableName) {
        return "DROP TABLE " + tableName;
    }

    @Override
    public String renderCreateIndex(String indexName, String tableName, String... columns) {
        StringJoiner joiner = new StringJoiner(", ");
        for (String col : columns) {
            joiner.add(col);
        }
        return "CREATE INDEX " + indexName + " ON " + tableName + " (" + joiner + ")";
    }

    @Override
    public String renderDropIndex(String indexName, String tableName) {
        return "DROP INDEX " + indexName + " ON " + tableName;
    }

    @Override
    public String renderRenameTable(String oldName, String newName) {
        return "RENAME TABLE " + oldName + " TO " + newName;
    }

    @Override
    public String renderRenameColumn(String tableName, String oldName, String newName) {
        return "ALTER TABLE " + tableName + " CHANGE COLUMN " + oldName + " " + newName;
    }

    @Override
    public String renderForeignKey(ForeignKeyDefinition fk) {
        return "CONSTRAINT fk_" + fk.getColumnName() +
            " FOREIGN KEY (" + fk.getColumnName() + ") REFERENCES " +
            fk.getReferencedTable() + "(" + fk.getReferencedColumn() + ")";
    }

    @Override
    public String renderColumnDefinition(ColumnDefinition column) {
        StringBuilder sb = new StringBuilder(column.getName());
        sb.append(" ").append(renderColumnType(column));

        if (!column.isNullable()) {
            sb.append(" NOT NULL");
        }

        if (column.isAutoIncrement()) {
            sb.append(" AUTO_INCREMENT");
        }

        if (column.getDefaultValue() != null) {
            sb.append(" DEFAULT ").append(column.getDefaultValue());
        }

        if (column.isPrimaryKey()) {
            sb.append(" PRIMARY KEY");
        }

        if (column.isUnique()) {
            sb.append(" UNIQUE");
        }

        if (column.getComment() != null) {
            sb.append(" COMMENT '").append(column.getComment()).append("'");
        }

        return sb.toString();
    }

    private String renderColumnType(ColumnDefinition column) {
        String type = column.getType();
        if ("VARCHAR".equals(type) && column.getLength() != null) {
            return type + "(" + column.getLength() + ")";
        }
        if ("DECIMAL".equals(type) && column.getPrecision() != null) {
            return type + "(" + column.getPrecision() + "," + column.getScale() + ")";
        }
        return type;
    }
}
