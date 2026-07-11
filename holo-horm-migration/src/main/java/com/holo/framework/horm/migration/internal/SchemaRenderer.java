package com.holo.framework.horm.migration.internal;

import com.holo.framework.horm.core.dialect.Dialect;
import com.holo.framework.horm.core.dialect.H2Dialect;
import com.holo.framework.horm.core.dialect.PostgresDialect;

/**
 * Schema 渲染器 SPI，将 TableDefinition 转换为数据库特定的 DDL SQL。
 */
public interface SchemaRenderer {

    /**
     * 渲染 CREATE TABLE DDL。
     *
     * @param table 表定义
     * @return DDL SQL 语句
     */
    String renderCreateTable(TableDefinition table);

    /**
     * 渲染 ALTER TABLE ADD COLUMN DDL。
     *
     * @param tableName 表名
     * @param column    列定义
     * @return DDL SQL 语句
     */
    String renderAddColumn(String tableName, ColumnDefinition column);

    /**
     * 渲染 ALTER TABLE DROP COLUMN DDL。
     *
     * @param tableName  表名
     * @param columnName 列名
     * @return DDL SQL 语句
     */
    String renderDropColumn(String tableName, String columnName);

    /**
     * 渲染 ALTER TABLE MODIFY COLUMN DDL。
     *
     * @param tableName 表名
     * @param column    列定义
     * @return DDL SQL 语句
     */
    String renderModifyColumn(String tableName, ColumnDefinition column);

    /**
     * 渲染 DROP TABLE DDL。
     *
     * @param tableName 表名
     * @return DDL SQL 语句
     */
    String renderDropTable(String tableName);

    /**
     * 渲染 CREATE INDEX DDL。
     *
     * @param indexName 索引名
     * @param tableName 表名
     * @param columns   列名
     * @return DDL SQL 语句
     */
    String renderCreateIndex(String indexName, String tableName, String... columns);

    /**
     * 渲染 DROP INDEX DDL。
     *
     * @param indexName 索引名
     * @param tableName 表名
     * @return DDL SQL 语句
     */
    String renderDropIndex(String indexName, String tableName);

    /**
     * 渲染 RENAME TABLE DDL。
     *
     * @param oldName 旧表名
     * @param newName 新表名
     * @return DDL SQL 语句
     */
    String renderRenameTable(String oldName, String newName);

    /**
     * 渲染 RENAME COLUMN DDL。
     *
     * @param tableName 表名
     * @param oldName   旧列名
     * @param newName   新列名
     * @return DDL SQL 语句
     */
    String renderRenameColumn(String tableName, String oldName, String newName);

    /**
     * 渲染外键约束 DDL（作为 CREATE TABLE 的一部分）。
     *
     * @param fk 外键定义
     * @return DDL SQL 片段
     */
    String renderForeignKey(ForeignKeyDefinition fk);

    /**
     * 渲染列定义 DDL（作为 CREATE TABLE 的一部分）。
     *
     * @param column 列定义
     * @return DDL SQL 片段
     */
    String renderColumnDefinition(ColumnDefinition column);

    /**
     * 根据方言选择合适的 SchemaRenderer 实现。
     *
     * <p>选择逻辑：
     * <ul>
     *   <li>PostgresDialect -> PostgresSchemaRenderer</li>
     *   <li>H2Dialect (PostgreSQL 模式) -> PostgresSchemaRenderer</li>
     *   <li>其他 -> MySQLSchemaRenderer (默认)</li>
     * </ul>
     *
     * @param dialect 数据库方言
     * @return 对应的 SchemaRenderer 实例
     */
    static SchemaRenderer forDialect(Dialect dialect) {
        if (dialect instanceof PostgresDialect) {
            return new PostgresSchemaRenderer();
        }
        if (dialect instanceof H2Dialect h2 && "h2-postgresql".equals(h2.name())) {
            return new PostgresSchemaRenderer();
        }
        return new MySQLSchemaRenderer();
    }
}
