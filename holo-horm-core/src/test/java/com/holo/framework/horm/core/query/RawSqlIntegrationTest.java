package com.holo.framework.horm.core.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.Repository;
import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.User;
import com.holo.framework.horm.meta.RowMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RawSqlIntegrationTest {

    private Connection conn;

    record UserSummary(String email, long id) {}

    @BeforeAll
    void setup() throws SQLException {
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
            st.execute("INSERT INTO users (email, created_at, updated_at) VALUES ('a@holo.dev', '2026-07-01 00:00:00', '2026-07-01 00:00:00')");
            st.execute("INSERT INTO users (email, created_at, updated_at) VALUES ('b@other.com', '2026-07-02 00:00:00', '2026-07-02 00:00:00')");
            st.execute("INSERT INTO users (email, created_at, updated_at) VALUES ('c@holo.dev', '2026-07-03 00:00:00', '2026-07-03 00:00:00')");
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
    void rawSqlQueryReturnsMappedRows() {
        List<UserSummary> summaries = Horm.rawSql().query(
            "SELECT email, id FROM users WHERE email LIKE ?",
            rs -> new UserSummary(rs.getString("email"), rs.getLong("id")),
            "%@holo.dev"
        );

        assertThat(summaries).hasSize(2);
        assertThat(summaries).extracting(UserSummary::email)
            .containsExactlyInAnyOrder("a@holo.dev", "c@holo.dev");
    }

    @Test
    void rawSqlQueryOneReturnsFirstRow() {
        Optional<UserSummary> summary = Horm.rawSql().queryOne(
            "SELECT email, id FROM users WHERE email = ?",
            rs -> new UserSummary(rs.getString("email"), rs.getLong("id")),
            "a@holo.dev"
        );

        assertThat(summary).isPresent();
        assertThat(summary.get().email()).isEqualTo("a@holo.dev");
    }

    @Test
    void rawSqlQueryOneReturnsEmptyWhenNoMatch() {
        Optional<UserSummary> summary = Horm.rawSql().queryOne(
            "SELECT email, id FROM users WHERE email = ?",
            rs -> new UserSummary(rs.getString("email"), rs.getLong("id")),
            "nonexistent@holo.dev"
        );

        assertThat(summary).isEmpty();
    }

    @Test
    void rawSqlUpdateReturnsAffectedRows() {
        long affected = Horm.rawSql().update(
            "UPDATE users SET updated_at = ? WHERE email LIKE ?",
            Instant.now(), "%@holo.dev"
        );

        assertThat(affected).isEqualTo(2);
    }

    @Test
    void repositoryRawQueryReturnsEntities() {
        Repository<User> repo = Horm.repository(User.class);
        List<User> users = repo.rawQuery("SELECT * FROM users WHERE id > ?", 0L);

        assertThat(users).hasSizeGreaterThanOrEqualTo(3);
        assertThat(users).extracting(User::getEmail)
            .contains("a@holo.dev", "b@other.com", "c@holo.dev");
    }

    @Test
    void repositoryRawQueryWithCustomMapper() {
        Repository<User> repo = Horm.repository(User.class);
        List<String> emails = repo.rawQuery(
            "SELECT email FROM users",
            rs -> rs.getString("email")
        );

        assertThat(emails).hasSizeGreaterThanOrEqualTo(3);
        assertThat(emails).contains("a@holo.dev", "b@other.com", "c@holo.dev");
    }
}
