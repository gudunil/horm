package com.holo.framework.horm.migration;

/**
 * 列构建器，提供链式列属性定义。
 *
 * <p>示例：
 * <pre>{@code
 * t.string("email", 128).notNull().unique().defaultVal("test@example.com");
 * t.integer("age").nullable().defaultVal(0);
 * t.bigIncrements("id").primaryKey();
 * t.string("status", 16).index();
 * t.integer("user_id").references("users", "id");
 * }</pre>
 */
public interface ColumnBuilder {

    /**
     * 设置列为 NOT NULL。
     *
     * @return 列构建器
     */
    ColumnBuilder notNull();

    /**
     * 设置列为 nullable（默认行为）。
     *
     * @return 列构建器
     */
    ColumnBuilder nullable();

    /**
     * 设置列唯一约束。
     *
     * @return 列构建器
     */
    ColumnBuilder unique();

    /**
     * 设置列默认值。
     *
     * @param value 默认值（字符串形式，如 "0"、"'active'"、"CURRENT_TIMESTAMP"）
     * @return 列构建器
     */
    ColumnBuilder defaultVal(String value);

    /**
     * 设置列为主键。
     *
     * @return 列构建器
     */
    ColumnBuilder primaryKey();

    /**
     * 设置列为自增（仅用于整数类型）。
     *
     * @return 列构建器
     */
    ColumnBuilder autoIncrement();

    /**
     * 为该列创建索引。
     *
     * @return 列构建器
     */
    ColumnBuilder index();

    /**
     * 设置外键引用。
     *
     * @param referencedTable  引用表名
     * @param referencedColumn 引用列名
     * @return 列构建器
     */
    ColumnBuilder references(String referencedTable, String referencedColumn);

    /**
     * 设置注释。
     *
     * @param comment 注释内容
     * @return 列构建器
     */
    ColumnBuilder comment(String comment);
}
