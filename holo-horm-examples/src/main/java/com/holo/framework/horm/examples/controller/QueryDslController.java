package com.holo.framework.horm.examples.controller;

import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.examples.entity.Order;
import com.holo.framework.horm.examples.entity.Product;
import com.holo.framework.horm.examples.entity.User;
import com.holo.framework.horm.examples.entity.generated.OrderAggMeta;
import com.holo.framework.horm.examples.entity.generated.OrderQueryMeta;
import com.holo.framework.horm.examples.entity.generated.ProductAggMeta;
import com.holo.framework.horm.examples.entity.generated.ProductQueryMeta;
import com.holo.framework.horm.examples.entity.generated.UserAggMeta;
import com.holo.framework.horm.examples.entity.generated.UserQueryMeta;
import com.holo.framework.horm.meta.Row;
import com.holo.framework.horm.meta.query.Conditions;
import com.holo.framework.horm.meta.query.expr.Aggregates;
import com.holo.framework.horm.meta.query.expr.Functions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M11 函数 DSL 查询示例控制器。
 * 演示函数表达式、聚合查询、GROUP BY/HAVING、投影查询和原始 SQL 的用法。
 */
@RestController
@RequestMapping("/api/dsl")
public class QueryDslController {

    /**
     * 示例 1: 函数表达式 - 查询大写邮箱
     * SQL: SELECT UPPER(email) FROM users
     */
    @GetMapping("/functions/upper-email")
    public List<String> upperCaseEmails() {
        return Model.query(User.class)
            .selectExpr(Functions.upper(UserQueryMeta.EMAIL))
            .listScalar(String.class);
    }

    /**
     * 示例 2: 日期函数 - 按年份分组统计订单数
     * SQL: SELECT YEAR(created_at) AS order_year, COUNT(*) FROM orders GROUP BY YEAR(created_at)
     */
    @GetMapping("/aggregate/orders-by-year")
    public List<Map<String, Object>> ordersGroupedByYear() {
        var yearExpr = Functions.year(OrderQueryMeta.CREATED_AT);
        List<Row> rows = Model.query(Order.class)
            .groupBy(yearExpr)
            .selectExpr(
                Functions.alias(yearExpr, "order_year"),
                Aggregates.alias(Aggregates.count(), "order_count"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "year", row.getInteger("order_year"),
                "count", row.getLong("order_count")))
            .toList();
    }

    /**
     * 示例 3: 聚合查询 - 每个用户的订单总金额和平均金额
     * SQL: SELECT user_id, SUM(total_price), AVG(total_price) FROM orders GROUP BY user_id
     */
    @GetMapping("/aggregate/user-order-summary")
    public List<Map<String, Object>> userOrderSummary() {
        List<Row> rows = Model.query(Order.class)
            .groupBy(OrderQueryMeta.USER_ID)
            .selectExpr(
                OrderQueryMeta.USER_ID,
                Aggregates.alias(OrderAggMeta.SUM_TOTAL_PRICE, "total_amount"),
                Aggregates.alias(OrderAggMeta.AVG_TOTAL_PRICE, "avg_amount"),
                Aggregates.alias(Aggregates.count(), "order_count"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "userId", row.getLong(OrderQueryMeta.USER_ID.column()),
                "totalAmount", row.getBigDecimal("total_amount"),
                "avgAmount", row.getBigDecimal("avg_amount"),
                "orderCount", row.getLong("order_count")))
            .toList();
    }

    /**
     * 示例 4: HAVING 过滤 - 订单数大于指定数量的用户
     * SQL: SELECT user_id, COUNT(*) FROM orders GROUP BY user_id HAVING COUNT(*) > ?
     */
    @GetMapping("/aggregate/users-with-min-orders")
    public List<Map<String, Object>> usersWithMinOrders(
            @RequestParam(defaultValue = "2") int minOrders) {
        List<Row> rows = Model.query(Order.class)
            .groupBy(OrderQueryMeta.USER_ID)
            .having(Conditions.raw("COUNT(*) > ?", minOrders))
            .selectExpr(
                OrderQueryMeta.USER_ID,
                Aggregates.alias(Aggregates.count(), "order_count"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "userId", row.getLong(OrderQueryMeta.USER_ID.column()),
                "orderCount", row.getLong("order_count")))
            .toList();
    }

    /**
     * 示例 5: 投影查询 - 查询所有用户总数
     * SQL: SELECT COUNT(*) FROM users
     */
    @GetMapping("/projection/user-count")
    public Map<String, Object> userCount() {
        Optional<Row> row = Model.query(User.class)
            .selectExpr(
                Aggregates.alias(Aggregates.count(), "total_count"))
            .firstRow();

        return row.map(r -> Map.<String, Object>of(
            "count", r.getLong("total_count")))
            .orElse(Map.of("count", 0));
    }

    /**
     * 示例 6: 原始 SQL - 执行自定义查询
     * 使用 Horm.rawSql() 执行不绑定实体类型的原始 SQL
     */
    @GetMapping("/raw/order-stats")
    public Map<String, Object> rawOrderStats() {
        List<Row> rows = Horm.rawSql()
            .query(
                "SELECT COUNT(*) AS total_orders, " +
                "SUM(total_price) AS total_revenue, " +
                "AVG(total_price) AS avg_order_value " +
                "FROM orders",
                rs -> {
                    Row row = Row.create("stats");
                    row.set("total_orders", rs.getLong(1));
                    row.set("total_revenue", rs.getBigDecimal(2));
                    row.set("avg_order_value", rs.getBigDecimal(3));
                    return row;
                });

        if (rows.isEmpty()) {
            return Map.of("totalOrders", 0, "totalRevenue", 0, "avgOrderValue", 0);
        }

        Row stats = rows.get(0);
        return Map.of(
            "totalOrders", stats.getLong("total_orders"),
            "totalRevenue", stats.getBigDecimal("total_revenue"),
            "avgOrderValue", stats.getBigDecimal("avg_order_value"));
    }

    /**
     * 示例 7: 聚合 + Java 层排序 - 最畅销的商品（按销量排序）
     * SQL: SELECT product_id, SUM(amount) AS total_sold FROM orders GROUP BY product_id
     * 然后在 Java 层按 total_sold 降序排序
     */
    @GetMapping("/aggregate/best-selling-products")
    public List<Map<String, Object>> bestSellingProducts(
            @RequestParam(defaultValue = "10") int limit) {
        List<Row> rows = Model.query(Order.class)
            .groupBy(OrderQueryMeta.PRODUCT_ID)
            .selectExpr(
                OrderQueryMeta.PRODUCT_ID,
                Aggregates.alias(OrderAggMeta.SUM_AMOUNT, "total_sold"))
            .listRows();

        return rows.stream()
            .sorted((r1, r2) -> Long.compare(
                r2.getLong("total_sold"),
                r1.getLong("total_sold")))
            .limit(limit)
            .map(row -> Map.<String, Object>of(
                "productId", row.getLong(OrderQueryMeta.PRODUCT_ID.column()),
                "totalSold", row.getLong("total_sold")))
            .toList();
    }

    /**
     * 示例 8: 日期函数组合 - 按年月统计订单
     * SQL: SELECT YEAR(created_at) AS order_year, MONTH(created_at) AS order_month, COUNT(*)
     *      FROM orders GROUP BY YEAR(created_at), MONTH(created_at)
     */
    @GetMapping("/aggregate/orders-by-month")
    public List<Map<String, Object>> ordersGroupedByMonth() {
        var yearExpr = Functions.year(OrderQueryMeta.CREATED_AT);
        var monthExpr = Functions.month(OrderQueryMeta.CREATED_AT);
        List<Row> rows = Model.query(Order.class)
            .groupBy(yearExpr, monthExpr)
            .selectExpr(
                Functions.alias(yearExpr, "order_year"),
                Functions.alias(monthExpr, "order_month"),
                Aggregates.alias(Aggregates.count(), "order_count"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "year", row.getInteger("order_year"),
                "month", row.getInteger("order_month"),
                "count", row.getLong("order_count")))
            .toList();
    }

    /**
     * 示例 9: 字符串函数组合 - 查询用户名长度
     * SQL: SELECT nickname, LENGTH(nickname) AS name_length FROM users WHERE LENGTH(nickname) > ?
     */
    @GetMapping("/functions/nickname-length")
    public List<Map<String, Object>> nicknameLengths(
            @RequestParam(defaultValue = "3") int minLength) {
        var lengthExpr = Functions.length(UserQueryMeta.NICKNAME);
        List<Row> rows = Model.query(User.class)
            .where(lengthExpr.gt(minLength))
            .selectExpr(
                UserQueryMeta.NICKNAME,
                Functions.alias(lengthExpr, "name_length"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "nickname", row.getString(UserQueryMeta.NICKNAME.column()),
                "length", row.getInteger("name_length")))
            .toList();
    }

    /**
     * 示例 10: 多聚合函数 - 商品价格统计
     * SQL: SELECT COUNT(*), MAX(price), MIN(price), AVG(price) FROM products
     */
    @GetMapping("/aggregate/product-price-stats")
    public Map<String, Object> productPriceStats() {
        Optional<Row> row = Model.query(Product.class)
            .selectExpr(
                Aggregates.alias(ProductAggMeta.COUNT, "total_count"),
                Aggregates.alias(ProductAggMeta.MAX_PRICE, "max_price"),
                Aggregates.alias(ProductAggMeta.MIN_PRICE, "min_price"),
                Aggregates.alias(ProductAggMeta.AVG_PRICE, "avg_price"))
            .firstRow();

        if (row.isEmpty()) {
            return Map.of("count", 0, "maxPrice", 0, "minPrice", 0, "avgPrice", 0);
        }

        Row stats = row.get();
        return Map.of(
            "count", stats.getLong("total_count"),
            "maxPrice", stats.getBigDecimal("max_price"),
            "minPrice", stats.getBigDecimal("min_price"),
            "avgPrice", stats.getBigDecimal("avg_price"));
    }

    /**
     * 示例 11: LOWER 函数 - 查询小写邮箱
     * SQL: SELECT LOWER(email) FROM users
     */
    @GetMapping("/functions/lower-email")
    public List<String> lowerCaseEmails() {
        return Model.query(User.class)
            .selectExpr(Functions.lower(UserQueryMeta.EMAIL))
            .listScalar(String.class);
    }

    /**
     * 示例 12: TRIM 函数 - 去除昵称前后空格
     * SQL: SELECT TRIM(nickname) FROM users
     */
    @GetMapping("/functions/trim-nickname")
    public List<String> trimmedNicknames() {
        return Model.query(User.class)
            .selectExpr(Functions.trim(UserQueryMeta.NICKNAME))
            .listScalar(String.class);
    }

    /**
     * 示例 13: SUBSTRING 函数 - 截取邮箱前缀
     * SQL: SELECT SUBSTRING(email, 1, 5) FROM users
     */
    @GetMapping("/functions/email-prefix")
    public List<String> emailPrefixes() {
        return Model.query(User.class)
            .selectExpr(Functions.substring(UserQueryMeta.EMAIL, 1, 5))
            .listScalar(String.class);
    }

    /**
     * 示例 14: CONCAT 函数 - 组合用户信息
     * SQL: SELECT CONCAT(nickname, ' (', email, ')') FROM users
     */
    @GetMapping("/functions/user-display-name")
    public List<String> userDisplayNames() {
        return Model.query(User.class)
            .selectExpr(Functions.concat(
                UserQueryMeta.NICKNAME,
                Functions.raw("' ('", String.class),
                UserQueryMeta.EMAIL,
                Functions.raw("')'", String.class)))
            .listScalar(String.class);
    }

    /**
     * 示例 15: ABS 函数 - 查询订单金额的绝对值
     * SQL: SELECT ABS(total_price) FROM orders
     */
    @GetMapping("/functions/abs-total-price")
    public List<Map<String, Object>> absoluteTotalPrices() {
        var absExpr = Functions.abs(OrderQueryMeta.TOTAL_PRICE);
        List<Row> rows = Model.query(Order.class)
            .selectExpr(
                OrderQueryMeta.ID,
                Functions.alias(absExpr, "abs_price"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "orderId", row.getLong(OrderQueryMeta.ID.column()),
                "absPrice", row.getBigDecimal("abs_price")))
            .toList();
    }

    /**
     * 示例 16: ROUND 函数 - 四舍五入订单金额到整数
     * SQL: SELECT ROUND(total_price, 0) FROM orders
     */
    @GetMapping("/functions/round-total-price")
    public List<Map<String, Object>> roundedTotalPrices() {
        var roundExpr = Functions.round(OrderQueryMeta.TOTAL_PRICE, 0);
        List<Row> rows = Model.query(Order.class)
            .selectExpr(
                OrderQueryMeta.ID,
                Functions.alias(roundExpr, "rounded_price"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "orderId", row.getLong(OrderQueryMeta.ID.column()),
                "roundedPrice", row.getBigDecimal("rounded_price")))
            .toList();
    }

    /**
     * 示例 17: FLOOR 函数 - 向下取整订单金额
     * SQL: SELECT FLOOR(total_price) FROM orders
     */
    @GetMapping("/functions/floor-total-price")
    public List<Map<String, Object>> flooredTotalPrices() {
        var floorExpr = Functions.floor(OrderQueryMeta.TOTAL_PRICE);
        List<Row> rows = Model.query(Order.class)
            .selectExpr(
                OrderQueryMeta.ID,
                Functions.alias(floorExpr, "floored_price"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "orderId", row.getLong(OrderQueryMeta.ID.column()),
                "flooredPrice", row.getBigDecimal("floored_price")))
            .toList();
    }

    /**
     * 示例 18: CEIL 函数 - 向上取整订单金额
     * SQL: SELECT CEIL(total_price) FROM orders
     */
    @GetMapping("/functions/ceil-total-price")
    public List<Map<String, Object>> ceiledTotalPrices() {
        var ceilExpr = Functions.ceil(OrderQueryMeta.TOTAL_PRICE);
        List<Row> rows = Model.query(Order.class)
            .selectExpr(
                OrderQueryMeta.ID,
                Functions.alias(ceilExpr, "ceiled_price"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "orderId", row.getLong(OrderQueryMeta.ID.column()),
                "ceiledPrice", row.getBigDecimal("ceiled_price")))
            .toList();
    }

    /**
     * 示例 19: MONTH 函数 - 按月份分组统计订单
     * SQL: SELECT MONTH(created_at) AS order_month, COUNT(*) FROM orders GROUP BY MONTH(created_at)
     */
    @GetMapping("/aggregate/orders-by-month-number")
    public List<Map<String, Object>> ordersGroupedByMonthNumber() {
        var monthExpr = Functions.month(OrderQueryMeta.CREATED_AT);
        List<Row> rows = Model.query(Order.class)
            .groupBy(monthExpr)
            .selectExpr(
                Functions.alias(monthExpr, "order_month"),
                Aggregates.alias(Aggregates.count(), "order_count"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "month", row.getInteger("order_month"),
                "count", row.getLong("order_count")))
            .toList();
    }

    /**
     * 示例 20: DAY 函数 - 按日期分组统计订单
     * SQL: SELECT DAY(created_at) AS order_day, COUNT(*) FROM orders GROUP BY DAY(created_at)
     */
    @GetMapping("/aggregate/orders-by-day")
    public List<Map<String, Object>> ordersGroupedByDay() {
        var dayExpr = Functions.day(OrderQueryMeta.CREATED_AT);
        List<Row> rows = Model.query(Order.class)
            .groupBy(dayExpr)
            .selectExpr(
                Functions.alias(dayExpr, "order_day"),
                Aggregates.alias(Aggregates.count(), "order_count"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "day", row.getInteger("order_day"),
                "count", row.getLong("order_count")))
            .toList();
    }

    /**
     * 示例 21: COALESCE 函数 - 处理空值
     * SQL: SELECT COALESCE(nickname, email) AS display_name FROM users
     */
    @GetMapping("/functions/coalesce-display-name")
    public List<String> coalesceDisplayNames() {
        return Model.query(User.class)
            .selectExpr(Functions.coalesce(UserQueryMeta.NICKNAME, UserQueryMeta.EMAIL))
            .listScalar(String.class);
    }

    /**
     * 示例 22: COUNT(DISTINCT) - 统计不同用户数
     * SQL: SELECT COUNT(DISTINCT user_id) FROM orders
     */
    @GetMapping("/aggregate/distinct-user-count")
    public Map<String, Object> distinctUserCount() {
        Optional<Row> row = Model.query(Order.class)
            .selectExpr(
                Aggregates.alias(Aggregates.countDistinct(OrderQueryMeta.USER_ID), "distinct_users"))
            .firstRow();

        return row.map(r -> Map.<String, Object>of(
            "distinctUsers", r.getLong("distinct_users")))
            .orElse(Map.of("distinctUsers", 0));
    }

    /**
     * 示例 23: 多条件 WHERE + 聚合 - 统计特定状态订单
     * SQL: SELECT status, COUNT(*) FROM orders WHERE total_price > ? GROUP BY status
     */
    @GetMapping("/aggregate/orders-by-status")
    public List<Map<String, Object>> ordersByStatus(
            @RequestParam(defaultValue = "1000") double minPrice) {
        List<Row> rows = Model.query(Order.class)
            .where(OrderQueryMeta.TOTAL_PRICE.gt(new java.math.BigDecimal(String.valueOf(minPrice))))
            .groupBy(OrderQueryMeta.STATUS)
            .selectExpr(
                OrderQueryMeta.STATUS,
                Aggregates.alias(Aggregates.count(), "order_count"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "status", row.getString(OrderQueryMeta.STATUS.column()),
                "count", row.getLong("order_count")))
            .toList();
    }

    /**
     * 示例 24: HAVING + 聚合函数 - 平均订单金额大于指定值的用户
     * SQL: SELECT user_id, AVG(total_price) FROM orders GROUP BY user_id HAVING AVG(total_price) > ?
     */
    @GetMapping("/aggregate/users-with-high-avg-order")
    public List<Map<String, Object>> usersWithHighAvgOrder(
            @RequestParam(defaultValue = "500") double minAvg) {
        List<Row> rows = Model.query(Order.class)
            .groupBy(OrderQueryMeta.USER_ID)
            .having(Conditions.raw("AVG(total_price) > ?", new java.math.BigDecimal(String.valueOf(minAvg))))
            .selectExpr(
                OrderQueryMeta.USER_ID,
                Aggregates.alias(OrderAggMeta.AVG_TOTAL_PRICE, "avg_price"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "userId", row.getLong(OrderQueryMeta.USER_ID.column()),
                "avgPrice", row.getBigDecimal("avg_price")))
            .toList();
    }

    /**
     * 示例 25: 多字段投影 - 查询用户 ID、邮箱和注册年份
     * SQL: SELECT id, email, YEAR(created_at) AS reg_year FROM users
     */
    @GetMapping("/projection/user-reg-year")
    public List<Map<String, Object>> userRegistrationYears() {
        var yearExpr = Functions.year(UserQueryMeta.CREATED_AT);
        List<Row> rows = Model.query(User.class)
            .selectExpr(
                UserQueryMeta.ID,
                UserQueryMeta.EMAIL,
                Functions.alias(yearExpr, "reg_year"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "id", row.getLong(UserQueryMeta.ID.column()),
                "email", row.getString(UserQueryMeta.EMAIL.column()),
                "regYear", row.getInteger("reg_year")))
            .toList();
    }

    /**
     * 示例 26: 复合 GROUP BY - 按用户和年月统计订单
     * SQL: SELECT user_id, YEAR(created_at) AS order_year, MONTH(created_at) AS order_month, COUNT(*)
     *      FROM orders GROUP BY user_id, YEAR(created_at), MONTH(created_at)
     */
    @GetMapping("/aggregate/orders-by-user-and-month")
    public List<Map<String, Object>> ordersByUserAndMonth() {
        var yearExpr = Functions.year(OrderQueryMeta.CREATED_AT);
        var monthExpr = Functions.month(OrderQueryMeta.CREATED_AT);
        List<Row> rows = Model.query(Order.class)
            .groupBy(OrderQueryMeta.USER_ID, yearExpr, monthExpr)
            .selectExpr(
                OrderQueryMeta.USER_ID,
                Functions.alias(yearExpr, "order_year"),
                Functions.alias(monthExpr, "order_month"),
                Aggregates.alias(Aggregates.count(), "order_count"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "userId", row.getLong(OrderQueryMeta.USER_ID.column()),
                "year", row.getInteger("order_year"),
                "month", row.getInteger("order_month"),
                "count", row.getLong("order_count")))
            .toList();
    }

    /**
     * 示例 27: 原始 SQL 带参数 - 查询特定用户的订单统计
     * SQL: SELECT COUNT(*), SUM(total_price) FROM orders WHERE user_id = ?
     */
    @GetMapping("/raw/user-order-stats")
    public Map<String, Object> rawUserOrderStats(@RequestParam long userId) {
        List<Row> rows = Horm.rawSql()
            .query(
                "SELECT COUNT(*) AS order_count, SUM(total_price) AS total_spent " +
                "FROM orders WHERE user_id = ?",
                rs -> {
                    Row row = Row.create("stats");
                    row.set("order_count", rs.getLong(1));
                    row.set("total_spent", rs.getBigDecimal(2));
                    return row;
                },
                userId);

        if (rows.isEmpty()) {
            return Map.of("orderCount", 0, "totalSpent", 0);
        }

        Row stats = rows.get(0);
        return Map.of(
            "orderCount", stats.getLong("order_count"),
            "totalSpent", stats.getBigDecimal("total_spent"));
    }

    /**
     * 示例 28: listScalar 单值查询 - 获取最高订单金额
     * SQL: SELECT MAX(total_price) FROM orders
     */
    @GetMapping("/projection/max-order-price")
    public Map<String, Object> maxOrderPrice() {
        Optional<java.math.BigDecimal> maxPrice = Model.query(Order.class)
            .selectExpr(Aggregates.max(OrderQueryMeta.TOTAL_PRICE))
            .firstScalar(java.math.BigDecimal.class);

        return maxPrice.map(price -> Map.<String, Object>of("maxPrice", price))
            .orElse(Map.of("maxPrice", 0));
    }

    /**
     * 示例 29: 组合条件 + 聚合 - 统计每个价格区间的商品数量
     * SQL: SELECT 
     *        CASE WHEN price < 1000 THEN 'Low' 
     *             WHEN price < 5000 THEN 'Medium' 
     *             ELSE 'High' END AS price_range,
     *        COUNT(*) 
     *      FROM products GROUP BY price_range
     * 注意: 这里用多个查询模拟 CASE WHEN 逻辑
     */
    @GetMapping("/aggregate/products-by-price-range")
    public List<Map<String, Object>> productsByPriceRange() {
        long lowCount = Model.query(Product.class)
            .where(ProductQueryMeta.PRICE.lt(new java.math.BigDecimal("1000")))
            .count();
        long mediumCount = Model.query(Product.class)
            .where(
                ProductQueryMeta.PRICE.ge(new java.math.BigDecimal("1000")),
                ProductQueryMeta.PRICE.lt(new java.math.BigDecimal("5000")))
            .count();
        long highCount = Model.query(Product.class)
            .where(ProductQueryMeta.PRICE.ge(new java.math.BigDecimal("5000")))
            .count();

        return List.of(
            Map.of("range", "Low (<1000)", "count", lowCount),
            Map.of("range", "Medium (1000-5000)", "count", mediumCount),
            Map.of("range", "High (>=5000)", "count", highCount));
    }

    /**
     * 示例 30: 复合投影 - 查询订单 ID、金额、四舍五入金额和绝对值
     * SQL: SELECT id, total_price, ROUND(total_price, 0), ABS(total_price) FROM orders
     */
    @GetMapping("/projection/order-price-transformations")
    public List<Map<String, Object>> orderPriceTransformations(
            @RequestParam(defaultValue = "10") int limit) {
        var roundExpr = Functions.round(OrderQueryMeta.TOTAL_PRICE, 0);
        var absExpr = Functions.abs(OrderQueryMeta.TOTAL_PRICE);
        List<Row> rows = Model.query(Order.class)
            .limit(limit)
            .selectExpr(
                OrderQueryMeta.ID,
                OrderQueryMeta.TOTAL_PRICE,
                Functions.alias(roundExpr, "rounded"),
                Functions.alias(absExpr, "absolute"))
            .listRows();

        return rows.stream()
            .map(row -> Map.<String, Object>of(
                "id", row.getLong(OrderQueryMeta.ID.column()),
                "price", row.getBigDecimal(OrderQueryMeta.TOTAL_PRICE.column()),
                "rounded", row.getBigDecimal("rounded"),
                "absolute", row.getBigDecimal("absolute")))
            .toList();
    }
}
