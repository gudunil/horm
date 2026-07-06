package com.holo.framework.horm.migration;

import java.util.function.Consumer;

/**
 * Schema DSL 接口，提供 Rails 风格的数据库 schema 操作。
 *
 * <p>通过 {@link Migration#up(Schema)} 和 {@link Migration#down(Schema)} 方法传入，
 * 用于定义表结构、索引等数据库变更。
 *
 * <p>示例：
 * <pre>{@code
 * schema.createTable("users", t -> {
 *     t.bigIncrements("id");
 *     t.string("email", 128).notNull().unique();
 *     t.integer("age").defaultVal(0);
 *     t.timestamps();
 * });
 *
 * schema.createIndex("idx_users_email", "users", "email");
 *
 * schema.alterTable("users", t -> {
 *     t.string("phone", 20).nullable();
 * });
 *
 * schema.dropTable("users");
 * }</pre>
 */
public interface Schema {

    /**
     * 创建表。
     *
     * @param tableName 表名
     * @param builder   列定义回调
     */
    void createTable(String tableName, Consumer<TableBuilder> builder);

    /**
     * 修改表。
     *
     * @param tableName 表名
     * @param builder   列变更回调
     */
    void alterTable(String tableName, Consumer<TableBuilder> builder);

    /**
     * 删除表。
     *
     * @param tableName 表名
     */
    void dropTable(String tableName);

    /**
     * 创建索引。
     *
     * @param indexName 索引名
     * @param tableName 表名
     * @param columns   列名（可多列）
     */
    void createIndex(String indexName, String tableName, String... columns);

    /**
     * 删除索引。
     *
     * @param indexName 索引名
     * @param tableName 表名
     */
    void dropIndex(String indexName, String tableName);

    /**
     * 重命名表。
     *
     * @param oldName 旧表名
     * @param newName 新表名
     */
    void renameTable(String oldName, String newName);

    /**
     * 重命名列。
     *
     * @param tableName 表名
     * @param oldName   旧列名
     * @param newName   新列名
     */
    void renameColumn(String tableName, String oldName, String newName);
}
