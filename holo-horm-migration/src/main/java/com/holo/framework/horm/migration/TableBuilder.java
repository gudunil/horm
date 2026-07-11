package com.holo.framework.horm.migration;

/**
 * 表构建器，提供链式列定义 DSL。
 *
 * <p>通过 {@link Schema#createTable(String, java.util.function.Consumer)} 和
 * {@link Schema#alterTable(String, java.util.function.Consumer)} 方法传入。
 *
 * <p>示例：
 * <pre>{@code
 * t.bigIncrements("id");
 * t.string("email", 128).notNull().unique();
 * t.integer("age").defaultVal(0);
 * t.text("bio").nullable();
 * t.decimal("price", 10, 2).notNull();
 * t.boolean("active").defaultVal(true);
 * t.datetime("created_at").notNull();
 * t.timestamps(); // created_at + updated_at
 * t.foreignKey("user_id", "users", "id");
 * }</pre>
 */
public interface TableBuilder {

    /**
     * 添加 BIGINT 自增主键列。
     *
     * @param columnName 列名
     * @return 列构建器
     */
    ColumnBuilder bigIncrements(String columnName);

    /**
     * 添加 INT 自增主键列。
     *
     * @param columnName 列名
     * @return 列构建器
     */
    ColumnBuilder increments(String columnName);

    /**
     * 添加 BIGINT 列。
     *
     * @param columnName 列名
     * @return 列构建器
     */
    ColumnBuilder bigInteger(String columnName);

    /**
     * 添加 INT 列。
     *
     * @param columnName 列名
     * @return 列构建器
     */
    ColumnBuilder integer(String columnName);

    /**
     * 添加 SMALLINT 列。
     *
     * @param columnName 列名
     * @return 列构建器
     */
    ColumnBuilder smallInteger(String columnName);

    /**
     * 添加 TINYINT 列。
     *
     * @param columnName 列名
     * @return 列构建器
     */
    ColumnBuilder tinyInteger(String columnName);

    /**
     * 添加 VARCHAR 列。
     *
     * @param columnName 列名
     * @param length     长度
     * @return 列构建器
     */
    ColumnBuilder string(String columnName, int length);

    /**
     * 添加 TEXT 列。
     *
     * @param columnName 列名
     * @return 列构建器
     */
    ColumnBuilder text(String columnName);

    /**
     * 添加 DECIMAL 列。
     *
     * @param columnName  列名
     * @param precision   精度
     * @param scale       小数位
     * @return 列构建器
     */
    ColumnBuilder decimal(String columnName, int precision, int scale);

    /**
     * 添加 FLOAT 列。
     *
     * @param columnName 列名
     * @return 列构建器
     */
    ColumnBuilder float_(String columnName);

    /**
     * 添加 DOUBLE 列。
     *
     * @param columnName 列名
     * @return 列构建器
     */
    ColumnBuilder double_(String columnName);

    /**
     * 添加 BOOLEAN 列。
     *
     * @param columnName 列名
     * @return 列构建器
     */
    ColumnBuilder boolean_(String columnName);

    /**
     * 添加 DATE 列。
     *
     * @param columnName 列名
     * @return 列构建器
     */
    ColumnBuilder date(String columnName);

    /**
     * 添加 TIME 列。
     *
     * @param columnName 列名
     * @return 列构建器
     */
    ColumnBuilder time(String columnName);

    /**
     * 添加 DATETIME 列。
     *
     * @param columnName 列名
     * @return 列构建器
     */
    ColumnBuilder datetime(String columnName);

    /**
     * 添加 TIMESTAMP 列。
     *
     * @param columnName 列名
     * @return 列构建器
     */
    ColumnBuilder timestamp(String columnName);

    /**
     * 添加 DATETIME 类型的 created_at 和 updated_at 列（NOT NULL，默认当前时间）。
     *
     * <p>等价于：
     * <pre>{@code
     * t.datetime("created_at").notNull().defaultVal("CURRENT_TIMESTAMP");
     * t.datetime("updated_at").notNull().defaultVal("CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
     * }</pre>
     */
    void timestamps();

    /**
     * 添加外键约束。
     *
     * @param columnName       本表列名
     * @param referencedTable  引用表名
     * @param referencedColumn 引用列名
     * @return 表构建器
     */
    TableBuilder foreignKey(String columnName, String referencedTable, String referencedColumn);

    /**
     * 删除列（仅用于 alterTable）。
     *
     * @param columnName 列名
     * @return 表构建器
     */
    TableBuilder dropColumn(String columnName);

    /**
     * 修改列（仅用于 alterTable）。
     *
     * @param columnName 列名
     * @return 列构建器
     */
    ColumnBuilder modifyColumn(String columnName);
}
