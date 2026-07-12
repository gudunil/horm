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
import org.junit.jupiter.api.Assumptions;
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
 * Integration tests against a real PostgreSQL instance.
 *
 * <p>Requires PostgreSQL running at localhost:5432 with user=root, password=postgres.
 * Creates a dedicated test schema and cleans up afterward.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresRealIntegrationTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/horm_test_pg";
    private static final String USER = "root";
    private static final String PASS = "postgres";

    private Connection adminConn;

    @BeforeAll
    void setup() throws SQLException {
        EntityMetaRegistry.reload();

        // Connect to the default 'postgres' database; skip suite when PostgreSQL is unavailable.
        Connection setupConn;
        try {
            setupConn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/postgres", USER, PASS);
        } catch (SQLException e) {
            Assumptions.assumeTrue(false,
                "PostgreSQL not available at localhost:5432 — skipping integration tests: " + e.getMessage());
            return;
        }
        try (setupConn) {
            try (Statement st = setupConn.createStatement()) {
                st.execute("CREATE DATABASE horm_test_pg");
            } catch (SQLException e) {
                // 42P04 = database already exists on repeat runs — safe to continue
                if (!"42P04".equals(e.getSQLState())) {
                    throw e;
                }
            }
        }

        adminConn = DriverManager.getConnection(URL, USER, PASS);
        try (Statement st = adminConn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS users");
            st.execute(
                "CREATE TABLE users (" +
                "  id BIGSERIAL PRIMARY KEY, " +
                "  email VARCHAR(128) NOT NULL, " +
                "  created_at TIMESTAMP, " +
                "  updated_at TIMESTAMP" +
                ")"
            );
        }

        DataSourceRegistry registry = new DataSourceRegistry();
        DataSourceProvider provider = new DataSourceProvider() {
            @Override
            public Connection getConnection() throws SQLException {
                Connection c = DriverManager.getConnection(URL, USER, PASS);
                c.setAutoCommit(true);
                return c;
            }

            @Override
            public void releaseConnection(Connection connection) {
                try { if (connection != null) connection.close(); } catch (SQLException ignored) {}
            }
        };
        registry.registerDefault(provider);

        Horm.install(new HormContext(registry, null,
            Map.of("default", new PostgresDialect())));
    }

    @AfterAll
    void teardown() throws SQLException {
        if (adminConn != null) {
            try (Statement st = adminConn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS users");
            }
            adminConn.close();
        }
    }

    @AfterEach
    void cleanup() {
        TransactionManager.clear();
        try (var c = DriverManager.getConnection(URL, USER, PASS);
             var st = c.createStatement()) {
            st.execute("DELETE FROM users");
        } catch (SQLException ignored) {}
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

    // ===== CRUD =====

    @Test
    void saveInsertsAndBackfillsId() {
        User u = new User();
        u.setEmail("pg-save@test.com");
        u.setUpdatedAt(Instant.now());
        u.save();

        assertThat(u.getId()).isNotNull().isPositive();
    }

    @Test
    void findByIdReturnsSavedEntity() {
        User u = new User();
        u.setEmail("pg-find@test.com");
        u.setCreatedAt(Instant.parse("2026-07-04T10:00:00Z"));
        u.save();

        User found = User.find(User.class, u.getId());
        assertThat(found).isNotNull();
        assertThat(found.getEmail()).isEqualTo("pg-find@test.com");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void updateChangesEntity() {
        User u = new User();
        u.setEmail("pg-before@test.com");
        u.save();

        u.setEmail("pg-after@test.com");
        u.save();

        User found = User.find(User.class, u.getId());
        assertThat(found.getEmail()).isEqualTo("pg-after@test.com");
    }

    @Test
    void deleteRemovesEntity() {
        User u = new User();
        u.setEmail("pg-del@test.com");
        u.save();
        Long id = u.getId();

        u.delete();

        assertThat(User.find(User.class, id)).isNull();
    }

    // ===== Pagination =====

    @Test
    void limitReturnsCorrectRowCount() {
        User u1 = persist("pg-lim-1@t.c");
        User u2 = persist("pg-lim-2@t.c");
        User u3 = persist("pg-lim-3@t.c");

        List<User> result = Model.query(User.class)
            .where(EMAIL.like("pg-lim-%@t.c"))
            .orderBy(ID, Order.ASC)
            .limit(2)
            .list();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(User::getId)
            .containsExactly(u1.getId(), u2.getId());
    }

    @Test
    void limitWithOffsetPaginates() {
        persist("pg-page-1@t.c");
        persist("pg-page-2@t.c");
        persist("pg-page-3@t.c");
        persist("pg-page-4@t.c");

        List<User> result = Model.query(User.class)
            .where(EMAIL.like("pg-page-%@t.c"))
            .orderBy(ID, Order.ASC)
            .limit(2)
            .offset(2)
            .list();

        assertThat(result).hasSize(2);
    }

    // ===== Query =====

    @Test
    void findFirstReturnsPresentWhenMatch() {
        User u = persist("pg-ff@t.c");

        Optional<User> result = Model.query(User.class)
            .where(EMAIL.eq("pg-ff@t.c"))
            .findFirst();

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(u.getId());
    }

    @Test
    void existsReturnsCorrectBoolean() {
        persist("pg-exists@t.c");

        assertThat(Model.query(User.class).where(EMAIL.eq("pg-exists@t.c")).exists()).isTrue();
        assertThat(Model.query(User.class).where(EMAIL.eq("pg-nope@t.c")).exists()).isFalse();
    }

    @Test
    void countReturnsMatchCount() {
        long before = Model.query(User.class).where(EMAIL.like("pg-cnt-%@t.c")).count();
        persist("pg-cnt-a@t.c");
        persist("pg-cnt-b@t.c");

        assertThat(Model.query(User.class).where(EMAIL.like("pg-cnt-%@t.c")).count())
            .isEqualTo(before + 2);
    }

    // ===== Transaction =====

    @Test
    void txCommitPersistsEntity() {
        Horm.tx(() -> {
            User u = new User();
            u.setEmail("pg-tx-commit@test.com");
            u.setUpdatedAt(Instant.now());
            u.save();
        });

        assertThat(Model.query(User.class).where(EMAIL.eq("pg-tx-commit@test.com")).list()).hasSize(1);
    }

    @Test
    void txRollbackDiscardsChanges() {
        try {
            Horm.tx(() -> {
                User u = new User();
                u.setEmail("pg-tx-rollback@test.com");
                u.setUpdatedAt(Instant.now());
                u.save();
                throw new RuntimeException("Force rollback");
            });
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).isEqualTo("Force rollback");
        }

        assertThat(Model.query(User.class).where(EMAIL.eq("pg-tx-rollback@test.com")).list()).isEmpty();
    }
}
