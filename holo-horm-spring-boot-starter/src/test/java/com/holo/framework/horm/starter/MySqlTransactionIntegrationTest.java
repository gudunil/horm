package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.TransactionManager;
import com.holo.framework.horm.meta.annotation.Propagation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MySQL 事务集成测试，验证 HORM 事务管理器在真实 MySQL 下的 commit/rollback
 * 和传播行为（REQUIRED / REQUIRES_NEW）。
 *
 * <p>测试连接信息：{@code jdbc:mysql://localhost:3306/holo_test}，用户名 {@code root}，密码 {@code root}。
 * 若运行时 MySQL 不可达，测试会被 {@link assumeTrue} 跳过（而非失败）。
 *
 * <p>使用独立的 {@link DataSourceProvider}（每次创建新连接）以支持 REQUIRES_NEW
 * 传播级别获取独立物理连接。
 *
 * <p>运行方式：
 * <ul>
 *   <li>确保本地 MySQL 已启动（端口 3306，用户名 root，密码 root）</li>
 *   <li>执行 {@code mvn -f holo-horm-spring-boot-starter/pom.xml test -Dtest=MySqlTransactionIntegrationTest}</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlTransactionIntegrationTest {

    private static final String MYSQL_URL =
        "jdbc:mysql://localhost:3306/holo_test?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String MYSQL_USER = "root";
    private static final String MYSQL_PASSWORD = "root";

    private Connection conn;

    @BeforeAll
    void setup() throws SQLException {
        try {
            conn = DriverManager.getConnection(MYSQL_URL, MYSQL_USER, MYSQL_PASSWORD);
        } catch (SQLException e) {
            assumeTrue(false, "MySQL 不可达，跳过 MySQL 事务集成测试: " + e.getMessage());
            return;
        }

        EntityMetaRegistry.reload();

        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS mysql_test_entities");
            st.execute(
                "CREATE TABLE mysql_test_entities (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "  name VARCHAR(128) NOT NULL, " +
                "  created_at TIMESTAMP(3) NULL DEFAULT NULL" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }

        // 使用 DataSourceProvider 创建新连接，支持 REQUIRES_NEW 获取独立物理连接
        Horm.install(new HormContext((DataSourceProvider) () -> {
            Connection c = DriverManager.getConnection(MYSQL_URL, MYSQL_USER, MYSQL_PASSWORD);
            c.setAutoCommit(true);
            return c;
        }));
    }

    @AfterAll
    void teardown() throws SQLException {
        if (conn == null) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS mysql_test_entities");
        }
        conn.close();
    }

    @AfterEach
    void cleanup() throws SQLException {
        if (conn == null) {
            return;
        }
        TransactionManager.clear();
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM mysql_test_entities");
        }
    }

    @Test
    void txCommitPersistsData() throws SQLException {
        Horm.tx(() -> {
            MySqlTestEntity e = new MySqlTestEntity();
            e.setName("tx-commit");
            e.setCreatedAt(Instant.now());
            e.save();
        });

        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void txRollbackDiscardsData() throws SQLException {
        assertThatThrownBy(() -> Horm.tx(() -> {
            MySqlTestEntity e = new MySqlTestEntity();
            e.setName("tx-rollback");
            e.setCreatedAt(Instant.now());
            e.save();
            throw new RuntimeException("Force rollback");
        })).isInstanceOf(RuntimeException.class)
          .hasMessage("Force rollback");

        assertThat(countRows()).isEqualTo(0);
    }

    @Test
    void requiredNestedInnerJoinsOuterBothSucceed() throws SQLException {
        Horm.tx(() -> {
            MySqlTestEntity outer = new MySqlTestEntity();
            outer.setName("outer-required");
            outer.setCreatedAt(Instant.now());
            outer.save();

            Horm.tx(Propagation.REQUIRED, () -> {
                MySqlTestEntity inner = new MySqlTestEntity();
                inner.setName("inner-required");
                inner.setCreatedAt(Instant.now());
                inner.save();
            });
        });

        assertThat(countRows()).isEqualTo(2);
    }

    @Test
    void requiresNewInnerCommitsIndependently() throws SQLException {
        assertThatThrownBy(() -> Horm.tx(() -> {
            MySqlTestEntity outer = new MySqlTestEntity();
            outer.setName("outer-rollback");
            outer.setCreatedAt(Instant.now());
            outer.save();

            Horm.tx(Propagation.REQUIRES_NEW, () -> {
                MySqlTestEntity inner = new MySqlTestEntity();
                inner.setName("inner-committed");
                inner.setCreatedAt(Instant.now());
                inner.save();
            });

            throw new RuntimeException("Outer rollback");
        })).isInstanceOf(RuntimeException.class)
          .hasMessage("Outer rollback");

        // 内层 REQUIRES_NEW 在独立连接上已提交，外层回滚不影响
        assertThat(countRows()).isEqualTo(1);
        assertThat(rowExists("inner-committed")).isTrue();
        assertThat(rowExists("outer-rollback")).isFalse();
    }

    @Test
    void txCallableReturnsValue() {
        Long count = Horm.tx(() -> {
            MySqlTestEntity e1 = new MySqlTestEntity();
            e1.setName("callable-1");
            e1.setCreatedAt(Instant.now());
            e1.save();

            MySqlTestEntity e2 = new MySqlTestEntity();
            e2.setName("callable-2");
            e2.setCreatedAt(Instant.now());
            e2.save();

            return MySqlTestEntity.count(MySqlTestEntity.class);
        });

        assertThat(count).isEqualTo(2L);
    }

    // ===== Helper methods =====

    private long countRows() throws SQLException {
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM mysql_test_entities")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private boolean rowExists(String name) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT 1 FROM mysql_test_entities WHERE name = ? LIMIT 1")) {
            ps.setString(1, name);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
