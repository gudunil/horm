package com.holo.framework.horm.core.dialect;

import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.TransactionManager;
import com.holo.framework.horm.core.User;
import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import com.holo.framework.horm.core.query.Order;
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
import java.util.Map;
import java.util.Optional;

import static com.holo.framework.horm.core.generated.UserQueryMeta.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for PostgresDialect against an in-memory H2 database
 * running in MODE=PostgreSQL. Exercises the full dialect-aware pipeline:
 * PostgresDialect → HormContext → QueryImpl/JdbcRepository → H2 (PG mode).
 *
 * <p>Uses a separate in-memory database ({@code hormpg}) to avoid conflicts
 * with existing MySQL-mode tests that share the {@code horm} database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresDialectIntegrationTest {

    private static final String H2_URL = "jdbc:h2:mem:hormpg;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    private Connection conn;

    @BeforeAll
    void setup() throws SQLException {
        EntityMetaRegistry.reload();
        conn = DriverManager.getConnection(H2_URL);
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE users (" +
                "  id BIGSERIAL PRIMARY KEY, " +
                "  email VARCHAR(128) NOT NULL, " +
                "  created_at TIMESTAMP, " +
                "  updated_at TIMESTAMP" +
                ")");
        }

        // Set up HormContext with PostgresDialect for the default datasource
        DataSourceRegistry registry = new DataSourceRegistry();
        DataSourceProvider provider = new DataSourceProvider() {
            @Override
            public Connection getConnection() throws SQLException {
                Connection c = DriverManager.getConnection(H2_URL);
                c.setAutoCommit(true);
                return c;
            }

            @Override
            public void releaseConnection(Connection connection) {
                // no-op; H2 in-memory connections are managed externally
            }
        };
        registry.registerDefault(provider);

        Horm.install(new HormContext(registry, null,
            Map.of("default", new PostgresDialect())));
    }

    @AfterAll
    void teardown() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE users");
        }
        conn.close();
    }

    @AfterEach
    void cleanup() {
        TransactionManager.clear();
    }

    private User persist(String email) {
        return persist(email, null);
    }

    private User persist(String email, Instant createdAt) {
        User u = new User();
        u.setEmail(email);
        u.setCreatedAt(createdAt);
        u.setUpdatedAt(Instant.now());
        u.save();
        return u;
    }

    // ===== CRUD Tests =====

    @Test
    void saveInsertsAndBackfillsId() {
        User user = new User();
        user.setEmail("pg-save@b.com");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());

        user.save();

        assertThat(user.getId()).isNotNull();
        assertThat(user.getId()).isPositive();
    }

    @Test
    void findByIdReturnsSavedEntity() {
        User user = new User();
        user.setEmail("pg-find@b.com");
        user.setCreatedAt(Instant.parse("2026-07-04T10:00:00Z"));
        user.save();

        User found = User.find(User.class, user.getId());

        assertThat(found).isNotNull();
        assertThat(found.getEmail()).isEqualTo("pg-find@b.com");
        assertThat(found.getCreatedAt()).isEqualTo(Instant.parse("2026-07-04T10:00:00Z"));
    }

    @Test
    void updateChangesEntity() {
        User user = new User();
        user.setEmail("pg-update-before@b.com");
        user.save();

        user.setEmail("pg-update-after@b.com");
        user.save();

        User found = User.find(User.class, user.getId());
        assertThat(found).isNotNull();
        assertThat(found.getEmail()).isEqualTo("pg-update-after@b.com");
    }

    @Test
    void deleteRemovesEntity() {
        User user = new User();
        user.setEmail("pg-delete@b.com");
        user.save();
        Long id = user.getId();

        user.delete();

        User found = User.find(User.class, id);
        assertThat(found).isNull();
    }

    // ===== Pagination Tests =====

    @Test
    void limitReturnsCorrectRowCount() {
        User u1 = persist("pg-lim-1@b.c");
        User u2 = persist("pg-lim-2@b.c");
        User u3 = persist("pg-lim-3@b.c");

        List<User> result = Model.query(User.class)
            .where(EMAIL.like("pg-lim-%@b.c"))
            .orderBy(ID, Order.ASC)
            .limit(2)
            .list();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(User::getId)
            .containsExactly(u1.getId(), u2.getId());
    }

    @Test
    void limitWithOffsetPaginates() {
        User u1 = persist("pg-page-1@b.c");
        User u2 = persist("pg-page-2@b.c");
        User u3 = persist("pg-page-3@b.c");
        User u4 = persist("pg-page-4@b.c");

        List<User> result = Model.query(User.class)
            .where(EMAIL.like("pg-page-%@b.c"))
            .orderBy(ID, Order.ASC)
            .limit(2)
            .offset(2)
            .list();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(User::getId)
            .containsExactly(u3.getId(), u4.getId());
    }

    // ===== Transaction Tests =====

    @Test
    void txCommit_persistsEntity() {
        Horm.tx(() -> {
            User u = new User();
            u.setEmail("pg-tx-commit@b.com");
            u.setUpdatedAt(Instant.now());
            u.save();
        });

        List<User> found = Model.query(User.class)
            .where(EMAIL.eq("pg-tx-commit@b.com"))
            .list();
        assertThat(found).hasSize(1);
    }

    @Test
    void txRollback_discardsChanges() {
        try {
            Horm.tx(() -> {
                User u = new User();
                u.setEmail("pg-tx-rollback@b.com");
                u.setUpdatedAt(Instant.now());
                u.save();
                throw new RuntimeException("Force rollback");
            });
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).isEqualTo("Force rollback");
        }

        List<User> found = Model.query(User.class)
            .where(EMAIL.eq("pg-tx-rollback@b.com"))
            .list();
        assertThat(found).isEmpty();
    }

    // ===== Query Builder Tests =====

    @Test
    void whereEqReturnsMatchingRows() {
        User a1 = persist("pg-eq-a@b.c");
        User a2 = persist("pg-eq-a@b.c");
        User x = persist("pg-eq-x@y.z");

        List<User> result = Model.query(User.class)
            .where(EMAIL.eq("pg-eq-a@b.c"))
            .list();

        assertThat(result).extracting(User::getId)
            .containsExactlyInAnyOrder(a1.getId(), a2.getId())
            .doesNotContain(x.getId());
    }

    @Test
    void findFirstReturnsPresentWhenMatch() {
        User u = persist("pg-ff@b.c");

        Optional<User> result = Model.query(User.class)
            .where(EMAIL.eq("pg-ff@b.c"))
            .findFirst();

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(u.getId());
    }

    @Test
    void findFirstReturnsEmptyWhenNoMatch() {
        Optional<User> result = Model.query(User.class)
            .where(EMAIL.eq("pg-nonexistent@nowhere.invalid"))
            .findFirst();

        assertThat(result).isEmpty();
    }

    @Test
    void existsReturnsTrueForMatchAndFalseForNoMatch() {
        User u = persist("pg-exists@b.c");

        boolean present = Model.query(User.class)
            .where(EMAIL.eq("pg-exists@b.c"))
            .exists();
        boolean absent = Model.query(User.class)
            .where(EMAIL.eq("pg-exists-nope@b.c"))
            .exists();

        assertThat(present).isTrue();
        assertThat(absent).isFalse();
    }

    @Test
    void countWithWhereReturnsMatchCount() {
        long before = Model.query(User.class)
            .where(EMAIL.like("pg-count-%@b.c"))
            .count();

        persist("pg-count-a@b.c");
        persist("pg-count-b@b.c");

        long after = Model.query(User.class)
            .where(EMAIL.like("pg-count-%@b.c"))
            .count();

        assertThat(after).isEqualTo(before + 2);
    }
}
