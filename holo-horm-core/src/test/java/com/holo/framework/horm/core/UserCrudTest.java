package com.holo.framework.horm.core;

import org.junit.jupiter.api.AfterAll;
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

/**
 * End-to-end Active Record CRUD against an in-memory H2 database (MODE=MySQL).
 *
 * <p>Exercises the full M1 pipeline: {@code @Entity User} → APT generates
 * {@code UserMeta/UserMapper} → {@code EntityMetaRegistry} loads
 * {@code entities.idx} on first lookup → {@code JdbcRepository} drives
 * {@code save/find/all/delete/count} through {@code PreparedStatement}s.
 *
 * <p>All tests share a single H2 connection installed into {@link HormContext};
 * data accumulates across tests, so assertions use deltas or lower bounds
 * rather than absolute counts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserCrudTest {

    private Connection conn;

    @BeforeAll
    void setup() throws SQLException {
        // EntityMetaRegistry's static initializer runs once on first class load;
        // a prior test's @AfterEach clear() can leave the registry empty.
        // Re-scan the classpath so the APT-generated UserMeta is registered.
        EntityMetaRegistry.reload();
        conn = DriverManager.getConnection("jdbc:h2:mem:horm;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE users (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "  email VARCHAR(128) NOT NULL, " +
                "  created_at TIMESTAMP, " +
                "  updated_at TIMESTAMP" +
                ")");
        }
        Horm.install(new HormContext(conn));
    }

    @AfterAll
    void teardown() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE users");
        }
        conn.close();
    }

    @Test
    void saveInsertsAndBackfillsId() {
        User user = new User();
        user.setEmail("save@b.com");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());

        user.save();

        assertThat(user.getId()).isNotNull();
        assertThat(user.getId()).isPositive();
    }

    @Test
    void findByIdReturnsSavedEntity() {
        User user = new User();
        user.setEmail("find@b.com");
        user.setCreatedAt(Instant.parse("2026-07-04T10:00:00Z"));
        user.save();

        User found = User.find(User.class, user.getId());

        assertThat(found).isNotNull();
        assertThat(found.getEmail()).isEqualTo("find@b.com");
        assertThat(found.getCreatedAt()).isEqualTo(Instant.parse("2026-07-04T10:00:00Z"));
    }

    @Test
    void findByIdReturnsNullWhenNotFound() {
        User found = User.find(User.class, 999_999L);

        assertThat(found).isNull();
    }

    @Test
    void allReturnsAllSavedEntities() {
        User u1 = new User();
        u1.setEmail("all1@b.com");
        u1.save();

        User u2 = new User();
        u2.setEmail("all2@b.com");
        u2.save();

        List<User> all = User.all(User.class);

        assertThat(all.size()).isGreaterThanOrEqualTo(2);
        assertThat(all).extracting(User::getEmail).contains("all1@b.com", "all2@b.com");
    }

    @Test
    void deleteRemovesEntity() {
        User user = new User();
        user.setEmail("delete@b.com");
        user.save();
        Long id = user.getId();

        user.delete();

        User found = User.find(User.class, id);
        assertThat(found).isNull();
    }

    @Test
    void countReturnsCorrectNumber() {
        long before = User.count(User.class);

        User u = new User();
        u.setEmail("count@b.com");
        u.save();

        long after = User.count(User.class);
        assertThat(after).isEqualTo(before + 1);
    }
}
