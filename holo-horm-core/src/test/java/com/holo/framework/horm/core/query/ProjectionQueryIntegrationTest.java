package com.holo.framework.horm.core.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.User;
import com.holo.framework.horm.core.generated.UserQueryMeta;
import com.holo.framework.horm.meta.Row;
import com.holo.framework.horm.meta.query.Conditions;
import com.holo.framework.horm.meta.query.expr.Aggregates;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectionQueryIntegrationTest {

    private Connection conn;

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
            st.execute("INSERT INTO users (email, created_at, updated_at) VALUES ('alice@holo.dev', '2026-07-01 10:00:00', '2026-07-01 10:00:00')");
            st.execute("INSERT INTO users (email, created_at, updated_at) VALUES ('bob@other.com', '2026-07-02 11:00:00', '2026-07-02 11:00:00')");
            st.execute("INSERT INTO users (email, created_at, updated_at) VALUES ('carol@holo.dev', '2026-07-03 12:00:00', '2026-07-03 12:00:00')");
            st.execute("INSERT INTO users (email, created_at, updated_at) VALUES ('dave@holo.dev', '2026-07-04 13:00:00', '2026-07-04 13:00:00')");
            st.execute("INSERT INTO users (email, created_at, updated_at) VALUES ('eve@other.com', '2026-07-05 14:00:00', '2026-07-05 14:00:00')");
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
    void selectExprCountReturnsRowCount() {
        List<Long> counts = Model.query(User.class)
            .selectExpr(Aggregates.alias(Aggregates.count(), "cnt"))
            .listScalar(Long.class);

        assertThat(counts).hasSize(1);
        assertThat(counts.get(0)).isEqualTo(5L);
    }

    @Test
    void selectExprWithGroupByReturnsGroups() {
        List<Row> rows = Model.query(User.class)
            .groupBy(UserQueryMeta.EMAIL)
            .selectExpr(UserQueryMeta.EMAIL, Aggregates.alias(Aggregates.count(), "cnt"))
            .listRows();

        assertThat(rows).hasSize(5);
        for (Row row : rows) {
            assertThat(row.has("cnt")).isTrue();
            assertThat(row.getLong("cnt")).isEqualTo(1L);
        }
    }

    @Test
    void selectExprWithHavingFiltersGroups() {
        List<Row> rows = Model.query(User.class)
            .groupBy(UserQueryMeta.EMAIL)
            .having(Conditions.rawWithLeading("COUNT(*) > ?", List.of(), 1L))
            .selectExpr(UserQueryMeta.EMAIL, Aggregates.alias(Aggregates.count(), "cnt"))
            .listRows();

        assertThat(rows).isEmpty();
    }

    @Test
    void firstScalarReturnsSingleValue() {
        Optional<Long> count = Model.query(User.class)
            .selectExpr(Aggregates.alias(Aggregates.count(), "cnt"))
            .firstScalar(Long.class);

        assertThat(count).isPresent();
        assertThat(count.get()).isEqualTo(5L);
    }

    @Test
    void firstRowReturnsRowWithColumns() {
        Optional<Row> row = Model.query(User.class)
            .selectExpr(Aggregates.alias(Aggregates.count(), "cnt"))
            .firstRow();

        assertThat(row).isPresent();
        assertThat(row.get().has("cnt")).isTrue();
        assertThat(row.get().getLong("cnt")).isEqualTo(5L);
    }

    @Test
    void listRowsReturnsMultipleRows() {
        List<Row> rows = Model.query(User.class)
            .selectExpr(UserQueryMeta.EMAIL)
            .listRows();

        assertThat(rows).hasSize(5);
        assertThat(rows).allSatisfy(row ->
            assertThat(row.contains("email")).isTrue()
        );
    }

    @Test
    void selectExprWithWhereCondition() {
        List<Long> counts = Model.query(User.class)
            .where(Conditions.like(UserQueryMeta.EMAIL, "%@holo.dev"))
            .selectExpr(Aggregates.alias(Aggregates.count(), "cnt"))
            .listScalar(Long.class);

        assertThat(counts).hasSize(1);
        assertThat(counts.get(0)).isEqualTo(3L);
    }

    @Test
    void selectExprWithOrderBy() {
        List<Row> rows = Model.query(User.class)
            .orderBy(UserQueryMeta.EMAIL, Order.ASC)
            .selectExpr(UserQueryMeta.EMAIL)
            .listRows();

        assertThat(rows).hasSize(5);
        assertThat(rows.get(0).getString("email")).isEqualTo("alice@holo.dev");
        assertThat(rows.get(4).getString("email")).isEqualTo("eve@other.com");
    }
}
