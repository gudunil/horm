package com.holo.framework.horm.migration.internal;

import java.util.StringJoiner;

/**
 * PostgreSQL 数据库 Schema 渲染器。
 *
 * <p>与 MySQLSchemaRenderer 的关键差异：
 * <ul>
 *   <li>CREATE TABLE 不带 ENGINE/CHARSET 后缀</li>
 *   <li>DROP INDEX 不需要 ON tableName</li>
 *   <li>RENAME TABLE 使用 ALTER TABLE ... RENAME TO ...</li>
 *   <li>RENAME COLUMN 使用 RENAME COLUMN ... TO ...</li>
 *   <li>MODIFY COLUMN 使用 ALTER COLUMN ... TYPE ...</li>
 *   <li>自增主键使用 BIGSERIAL</li>
 *   <li>布尔类型使用 BOOLEAN</li>
 *   <li>时间类型使用 TIMESTAMP</li>
 *   <li>列不支持 COMMENT（需单独 COMMENT ON 语句）</li>
 *   <li>标识符使用双引号引用</li>
 * </ul>
 */
public final class PostgresSchemaRenderer implements SchemaRenderer {

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
        sb.append("\n)");
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
        return "ALTER TABLE " + tableName + " ALTER COLUMN " + column.getName() +
            " TYPE " + renderColumnType(column);
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
        return "DROP INDEX " + indexName;
    }

    @Override
    public String renderRenameTable(String oldName, String newName) {
        return "ALTER TABLE " + oldName + " RENAME TO " + newName;
    }

    @Override
    public String renderRenameColumn(String tableName, String oldName, String newName) {
        return "ALTER TABLE " + tableName + " RENAME COLUMN " + oldName + " TO " + newName;
    }

    @Override
    public String renderForeignKey(ForeignKeyDefinition fk) {
        return "FOREIGN KEY (" + fk.getColumnName() + ") REFERENCES " +
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
            // PostgreSQL uses BIGSERIAL which already implies NOT NULL and auto-increment
            // BIGSERIAL is handled in renderColumnType, so we don't add AUTO_INCREMENT here
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

        // PostgreSQL does not support COMMENT on column inline;
        // requires separate COMMENT ON statements

        return sb.toString();
    }

    private String renderColumnType(ColumnDefinition column) {
        String type = column.getType();

        // Auto-increment primary key: use BIGSERIAL instead of BIGINT
        if (column.isAutoIncrement() && column.isPrimaryKey()) {
            if ("BIGINT".equals(type)) {
                return "BIGSERIAL";
            }
            if ("INT".equals(type)) {
                return "SERIAL";
            }
        }

        // Boolean type mapping
        if ("BOOLEAN".equals(type)) {
            return "BOOLEAN";
        }

        // Timestamp type mapping: DATETIME -> TIMESTAMP
        if ("DATETIME".equals(type)) {
            return "TIMESTAMP";
        }

        if ("VARCHAR".equals(type) && column.getLength() != null) {
            return type + "(" + column.getLength() + ")";
        }
        if ("DECIMAL".equals(type) && column.getPrecision() != null) {
            return type + "(" + column.getPrecision() + "," + column.getScale() + ")";
        }
        return type;
    }
}
