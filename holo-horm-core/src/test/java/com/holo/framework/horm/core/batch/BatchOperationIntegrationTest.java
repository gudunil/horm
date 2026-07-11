package com.holo.framework.horm.core.batch;

import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.TransactionManager;
import com.holo.framework.horm.core.query.UpdateQuery;
import com.holo.framework.horm.core.query.DeleteQuery;
import com.holo.framework.horm.meta.annotation.Propagation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.holo.framework.horm.core.batch.generated.ProductQueryMeta.*;

/**
 * H2 内存数据库集成测试：验证 HORM 批量 UPDATE/DELETE 操作。
 *
 * <p>使用与 TransactionIntegrationTest 相同的 DataSourceProvider 模式，
 * 每次调用都创建新的 H2 连接，以确保 TransactionManager 能正确工作。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BatchOperationIntegrationTest {

    private static final String H2_URL = "jdbc:h2:mem:horm;MODE=MySQL;DB_CLOSE_DELAY=-1";

    /** 用于 DDL、数据验证和清理的直接连接 */
    private Connection conn;

    @BeforeAll
    void setup() throws SQLException {
        EntityMetaRegistry.reload();

        conn = DriverManager.getConnection(H2_URL);
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS products (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "  name VARCHAR(128) NOT NULL, " +
                "  category VARCHAR(128) NOT NULL, " +
                "  price DECIMAL(19,2) NOT NULL" +
                ")");
        }

        // DataSourceProvider 每次调用创建新的 H2 连接，
        // 确保 TransactionManager 能获取独立连接以支持事务传播。
        Horm.install(new HormContext((DataSourceProvider) () -> {
            Connection c = DriverManager.getConnection(H2_URL);
            c.setAutoCommit(true);
            return c;
        }));
    }

    @AfterAll
    void teardown() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE products");
        }
        conn.close();
    }

    @AfterEach
    void cleanup() throws SQLException {
        TransactionManager.clear();
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM products");
        }
    }

    // ===== 辅助方法 =====

    /** 查询 products 表中的总行数 */
    private long countProducts() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM products")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** 按 category 查询行数 */
    private long countByCategory(String category) throws SQLException {
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM products WHERE category = '" + category + "'")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** 查询指定 category 的平均价格 */
    private BigDecimal avgPriceByCategory(String category) throws SQLException {
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT AVG(price) FROM products WHERE category = '" + category + "'")) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        }
    }

    /** 查询指定 id 的价格 */
    private BigDecimal getPriceById(long id) throws SQLException {
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT price FROM products WHERE id = " + id)) {
                return rs.next() ? rs.getBigDecimal(1) : null;
            }
        }
    }

    /** 创建未持久化的 Product 实例 */
    private Product newProduct(String name, String category, BigDecimal price) {
        Product p = new Product();
        p.setName(name);
        p.setCategory(category);
        p.setPrice(price);
        return p;
    }

    /** 安全计数（不抛受检异常），用于断言链 */
    private long countProductsSafe() {
        try {
            return countProducts();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ===== 测试用例 =====

    /** 批量更新：只更新匹配 WHERE 条件的行 */
    @Test
    void batchUpdate_updatesMatchingRows() throws SQLException {
        // 插入 3 个产品：2 个 books，1 个 electronics
        newProduct("Java编程思想", "books", BigDecimal.valueOf(99.0)).save();
        newProduct("Effective Java", "books", BigDecimal.valueOf(79.0)).save();
        newProduct("iPhone", "electronics", BigDecimal.valueOf(6999.0)).save();

        // 批量更新 books 分类的价格为 50.0
        int updated = Model.update(Product.class)
            .set(PRICE, BigDecimal.valueOf(50.0))
            .where(CATEGORY.eq("books"))
            .execute();

        // 应更新 2 行
        assertThat(updated).isEqualTo(2);

        // books 分类价格应该都变成 50.0
        assertThat(avgPriceByCategory("books")).isEqualByComparingTo(BigDecimal.valueOf(50.0));

        // electronics 分类价格应保持不变
        assertThat(avgPriceByCategory("electronics")).isEqualByComparingTo(BigDecimal.valueOf(6999.0));
    }

    /** 批量更新：同时更新多个字段 */
    @Test
    void batchUpdate_multipleFields() throws SQLException {
        Product p = newProduct("Java编程思想", "books", BigDecimal.valueOf(99.0));
        p.save();
        long savedId = p.getId();

        // 同时更新 name 和 category
        int updated = Model.update(Product.class)
            .set(NAME, "Java编程思想（第4版）")
            .set(CATEGORY, "programming")
            .where(CATEGORY.eq("books"))
            .execute();

        assertThat(updated).isEqualTo(1);

        // 验证 name 和 category 都已更新
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT name, category FROM products WHERE id = " + savedId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("name")).isEqualTo("Java编程思想（第4版）");
            assertThat(rs.getString("category")).isEqualTo("programming");
        }
    }

    /** 批量更新：不带 WHERE 条件时更新所有行 */
    @Test
    void batchUpdate_noWhere_updatesAll() throws SQLException {
        newProduct("Java编程思想", "books", BigDecimal.valueOf(99.0)).save();
        newProduct("iPhone", "electronics", BigDecimal.valueOf(6999.0)).save();

        // 不带 WHERE 条件，更新所有行的 price
        int updated = Model.update(Product.class)
            .set(PRICE, BigDecimal.valueOf(0.01))
            .execute();

        // 应更新 2 行
        assertThat(updated).isEqualTo(2);

        // 所有行价格都应变为 0.01
        assertThat(countProducts()).isEqualTo(2);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT price FROM products")) {
            while (rs.next()) {
                assertThat(rs.getBigDecimal("price")).isEqualByComparingTo(BigDecimal.valueOf(0.01));
            }
        }
    }

    /** 批量删除：只删除匹配 WHERE 条件的行 */
    @Test
    void batchDelete_deletesMatchingRows() throws SQLException {
        newProduct("Java编程思想", "books", BigDecimal.valueOf(99.0)).save();
        newProduct("Effective Java", "books", BigDecimal.valueOf(79.0)).save();
        newProduct("iPhone", "electronics", BigDecimal.valueOf(6999.0)).save();

        // 删除 books 分类的产品
        int deleted = Model.delete(Product.class)
            .where(CATEGORY.eq("books"))
            .execute();

        // 应删除 2 行
        assertThat(deleted).isEqualTo(2);

        // 剩余 1 行 electronics
        assertThat(countProducts()).isEqualTo(1);
        assertThat(countByCategory("electronics")).isEqualTo(1);
    }

    /** 批量删除：不带 WHERE 条件时删除所有行 */
    @Test
    void batchDelete_noWhere_deletesAll() throws SQLException {
        newProduct("Java编程思想", "books", BigDecimal.valueOf(99.0)).save();
        newProduct("iPhone", "electronics", BigDecimal.valueOf(6999.0)).save();

        // 不带 WHERE 条件，删除所有行
        int deleted = Model.delete(Product.class)
            .execute();

        // 应删除 2 行
        assertThat(deleted).isEqualTo(2);
        assertThat(countProducts()).isEqualTo(0);
    }

    /** 批量更新：事务内回滚后数据不变 */
    @Test
    void batchUpdate_inTransaction_rollback() throws SQLException {
        newProduct("Java编程思想", "books", BigDecimal.valueOf(99.0)).save();
        newProduct("Effective Java", "books", BigDecimal.valueOf(79.0)).save();

        // 记录更新前的价格
        BigDecimal priceBefore = avgPriceByCategory("books");

        // 在事务中执行批量更新，然后抛出异常触发回滚
        assertThatThrownBy(() -> Horm.tx(() -> {
            Model.update(Product.class)
                .set(PRICE, BigDecimal.valueOf(1.0))
                .where(CATEGORY.eq("books"))
                .execute();
            throw new RuntimeException("强制回滚");
        })).isInstanceOf(RuntimeException.class)
          .hasMessage("强制回滚");

        // 回滚后价格应保持不变
        assertThat(avgPriceByCategory("books")).isEqualByComparingTo(priceBefore);
    }

    /** 批量删除：事务内回滚后数据仍然存在 */
    @Test
    void batchDelete_inTransaction_rollback() throws SQLException {
        newProduct("Java编程思想", "books", BigDecimal.valueOf(99.0)).save();
        newProduct("Effective Java", "books", BigDecimal.valueOf(79.0)).save();

        // 记录删除前的行数
        long countBefore = countProducts();

        // 在事务中执行批量删除，然后抛出异常触发回滚
        assertThatThrownBy(() -> Horm.tx(() -> {
            Model.delete(Product.class)
                .where(CATEGORY.eq("books"))
                .execute();
            throw new RuntimeException("强制回滚");
        })).isInstanceOf(RuntimeException.class)
          .hasMessage("强制回滚");

        // 回滚后数据应仍然存在
        assertThat(countProductsSafe()).isEqualTo(countBefore);
        assertThat(countByCategory("books")).isEqualTo(2);
    }
}
