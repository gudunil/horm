package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MySQL 端到端集成测试，验证 HORM Active Record CRUD 管道在真实 MySQL 下的行为。
 *
 * <p>测试连接信息：{@code jdbc:mysql://localhost:3306/holo_test}，用户名 {@code root}，密码 {@code root}。
 * 若运行时 MySQL 不可达，测试会被 {@link assumeTrue} 跳过（而非失败）。
 *
 * <p>使用 {@code createDatabaseIfNotExist=true} 自动创建 {@code holo_test} 数据库。
 * 表结构在 {@code @BeforeAll} 中通过 DDL 创建，在 {@code @AfterAll} 中清理。
 * 每个测试后通过 {@code DELETE FROM} 清空数据，保证测试隔离。
 *
 * <p>运行方式：
 * <ul>
 *   <li>确保本地 MySQL 已启动（端口 3306，用户名 root，密码 root）</li>
 *   <li>执行 {@code mvn -f holo-horm-spring-boot-starter/pom.xml test -Dtest=MySqlIntegrationTest}</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlIntegrationTest {

    private static final String MYSQL_URL =
        "jdbc:mysql://localhost:3306/holo_test?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String MYSQL_USER = "root";
    private static final String MYSQL_PASSWORD = "root";

    private Connection conn;

    @BeforeAll
    void setup() throws SQLException {
        // 尝试连接 MySQL，若不可达则跳过所有测试
        try {
            conn = DriverManager.getConnection(MYSQL_URL, MYSQL_USER, MYSQL_PASSWORD);
        } catch (SQLException e) {
            assumeTrue(false, "MySQL 不可达，跳过 MySQL 集成测试: " + e.getMessage());
            return;
        }

        // APT 生成的元数据在首次类加载时注册；若前序测试清空了注册表需重新加载
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

        Horm.install(new HormContext(conn));
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
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM mysql_test_entities");
        }
    }

    @Test
    void saveInsertsAndBackfillsId() {
        MySqlTestEntity entity = new MySqlTestEntity();
        entity.setName("save-test");
        entity.setCreatedAt(Instant.now());

        entity.save();

        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getId()).isPositive();
    }

    @Test
    void findByIdReturnsSavedEntity() {
        MySqlTestEntity entity = new MySqlTestEntity();
        entity.setName("find-test");
        entity.setCreatedAt(Instant.parse("2026-07-07T10:00:00Z"));
        entity.save();

        MySqlTestEntity found = MySqlTestEntity.find(MySqlTestEntity.class, entity.getId());

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(entity.getId());
        assertThat(found.getName()).isEqualTo("find-test");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void findByIdReturnsNullWhenNotFound() {
        MySqlTestEntity found = MySqlTestEntity.find(MySqlTestEntity.class, 999_999L);

        assertThat(found).isNull();
    }

    @Test
    void findAllReturnsSavedEntities() {
        MySqlTestEntity e1 = new MySqlTestEntity();
        e1.setName("all-1");
        e1.setCreatedAt(Instant.now());
        e1.save();

        MySqlTestEntity e2 = new MySqlTestEntity();
        e2.setName("all-2");
        e2.setCreatedAt(Instant.now());
        e2.save();

        List<MySqlTestEntity> all = MySqlTestEntity.all(MySqlTestEntity.class);

        assertThat(all).hasSizeGreaterThanOrEqualTo(2);
        assertThat(all).extracting(MySqlTestEntity::getName)
            .contains("all-1", "all-2");
    }

    @Test
    void updateChangesName() {
        MySqlTestEntity entity = new MySqlTestEntity();
        entity.setName("before-update");
        entity.setCreatedAt(Instant.now());
        entity.save();

        entity.setName("after-update");
        entity.save();

        MySqlTestEntity found = MySqlTestEntity.find(MySqlTestEntity.class, entity.getId());
        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("after-update");
    }

    @Test
    void deleteRemovesEntity() {
        MySqlTestEntity entity = new MySqlTestEntity();
        entity.setName("delete-test");
        entity.setCreatedAt(Instant.now());
        entity.save();
        Long id = entity.getId();

        entity.delete();

        assertThat(MySqlTestEntity.find(MySqlTestEntity.class, id)).isNull();
    }

    @Test
    void countReturnsCorrectNumber() {
        long before = MySqlTestEntity.count(MySqlTestEntity.class);

        MySqlTestEntity e1 = new MySqlTestEntity();
        e1.setName("count-1");
        e1.setCreatedAt(Instant.now());
        e1.save();

        MySqlTestEntity e2 = new MySqlTestEntity();
        e2.setName("count-2");
        e2.setCreatedAt(Instant.now());
        e2.save();

        long after = MySqlTestEntity.count(MySqlTestEntity.class);
        assertThat(after - before).isEqualTo(2);
    }
}
