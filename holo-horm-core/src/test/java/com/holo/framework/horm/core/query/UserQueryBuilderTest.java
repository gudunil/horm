package com.holo.framework.horm.core.query;

import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.User;
import com.holo.framework.horm.meta.query.Condition;
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
import java.util.Optional;

import static com.holo.framework.horm.core.generated.UserQueryMeta.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for the {@code Query<T>} fluent API against
 * an in-memory H2 database (MODE=MySQL). Exercises the full M2 pipeline:
 * {@code @Entity User} → APT-generated {@code UserQueryMeta} TypedField
 * constants → {@link Model#query} → {@link QueryImpl} SQL rendering →
 * {@code PreparedStatement} execution against H2.
 *
 * <p>Each test inserts rows with unique email identifiers so assertions can
 * target exact result sets without interference from data accumulated by
 * other tests in the same shared H2 connection.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserQueryBuilderTest {

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

    @Test
    void whereEqReturnsMatchingRows() {
        User a1 = persist("eq-a@b.c");
        User a2 = persist("eq-a@b.c");
        User x = persist("eq-x@y.z");

        List<User> result = Model.query(User.class)
            .where(EMAIL.eq("eq-a@b.c"))
            .list();

        assertThat(result).extracting(User::getId)
            .containsExactlyInAnyOrder(a1.getId(), a2.getId())
            .doesNotContain(x.getId());
        assertThat(result).allSatisfy(u -> assertThat(u.getEmail()).isEqualTo("eq-a@b.c"));
    }

    @Test
    void whereLikeMatchesPattern() {
        User m1 = persist("like-1@b.c");
        User m2 = persist("like-2@b.c");
        User other = persist("like-other@x.z");

        List<User> result = Model.query(User.class)
            .where(EMAIL.like("%@b.c"))
            .list();

        assertThat(result).extracting(User::getId).contains(m1.getId(), m2.getId());
        assertThat(result).extracting(User::getId).doesNotContain(other.getId());
    }

    @Test
    void whereInMatchesList() {
        User u1 = persist("in-1@b.c");
        User u2 = persist("in-2@b.c");
        User u3 = persist("in-3@b.c");
        User u4 = persist("in-4@b.c");
        User u5 = persist("in-5@b.c");

        List<User> result = Model.query(User.class)
            .where(ID.in(List.of(u1.getId(), u3.getId(), u5.getId())))
            .list();

        assertThat(result).extracting(User::getId)
            .containsExactlyInAnyOrder(u1.getId(), u3.getId(), u5.getId());
    }

    @Test
    void whereBetweenFiltersRange() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-06-01T00:00:00Z");
        Instant t3 = Instant.parse("2026-12-01T00:00:00Z");
        User early = persist("bw-early@b.c", t1);
        User mid = persist("bw-mid@b.c", t2);
        User late = persist("bw-late@b.c", t3);

        List<User> result = Model.query(User.class)
            .where(CREATED_AT.between(
                Instant.parse("2026-03-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z")))
            .list();

        assertThat(result).extracting(User::getId).containsExactly(mid.getId());
    }

    @Test
    void whereAndCombinesConditions() {
        User u1 = persist("and-1@b.c");
        User u2 = persist("and-2@b.c");

        List<User> result = Model.query(User.class)
            .where(ID.gt(0L)).and(EMAIL.isNotNull())
            .list();

        assertThat(result).extracting(User::getId).contains(u1.getId(), u2.getId());
    }

    @Test
    void whereOrCombinesConditions() {
        User a = persist("or-a@b.c");
        User b = persist("or-b@b.c");
        User c = persist("or-c@b.c");

        List<User> result = Model.query(User.class)
            .where(Condition.or(EMAIL.eq("or-a@b.c"), EMAIL.eq("or-c@b.c")))
            .list();

        assertThat(result).extracting(User::getId)
            .containsExactlyInAnyOrder(a.getId(), c.getId())
            .doesNotContain(b.getId());
    }

    @Test
    void whereNotNegatesCondition() {
        User a = persist("not-a@b.c");
        User b = persist("not-b@b.c");
        User c = persist("not-c@b.c");

        List<User> result = Model.query(User.class)
            .where(Condition.not(EMAIL.eq("not-a@b.c")))
            .list();

        assertThat(result).extracting(User::getId)
            .contains(b.getId(), c.getId())
            .doesNotContain(a.getId());
    }

    @Test
    void orderByDescWithLimitReturnsTopN() {
        User u1 = persist("ord-1@b.c");
        User u2 = persist("ord-2@b.c");
        User u3 = persist("ord-3@b.c");
        User u4 = persist("ord-4@b.c");
        User u5 = persist("ord-5@b.c");

        List<User> result = Model.query(User.class)
            .where(EMAIL.like("ord-%@b.c"))
            .orderBy(ID, Order.DESC)
            .limit(2)
            .list();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isGreaterThan(result.get(1).getId());
        assertThat(result.get(0).getId()).isEqualTo(u5.getId());
        assertThat(result.get(1).getId()).isEqualTo(u4.getId());
    }

    @Test
    void orderByDescWithLimitOffsetPaginates() {
        User u1 = persist("page-1@b.c");
        User u2 = persist("page-2@b.c");
        User u3 = persist("page-3@b.c");
        User u4 = persist("page-4@b.c");
        User u5 = persist("page-5@b.c");

        List<User> result = Model.query(User.class)
            .where(EMAIL.like("page-%@b.c"))
            .orderBy(ID, Order.DESC)
            .limit(2)
            .offset(2)
            .list();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(u3.getId());
        assertThat(result.get(1).getId()).isEqualTo(u2.getId());
    }

    @Test
    void findFirstReturnsEmptyWhenNoMatch() {
        Optional<User> result = Model.query(User.class)
            .where(EMAIL.eq("nonexistent@nowhere.invalid"))
            .findFirst();

        assertThat(result).isEmpty();
    }

    @Test
    void findFirstReturnsPresentWhenMatch() {
        User u = persist("ff-1@b.c");

        Optional<User> result = Model.query(User.class)
            .where(EMAIL.eq("ff-1@b.c"))
            .findFirst();

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(u.getId());
    }

    @Test
    void countWithWhereReturnsMatchCount() {
        long before = Model.query(User.class)
            .where(EMAIL.like("count-%@b.c"))
            .count();

        persist("count-a@b.c");
        persist("count-b@b.c");

        long after = Model.query(User.class)
            .where(EMAIL.like("count-%@b.c"))
            .count();

        assertThat(after).isEqualTo(before + 2);
    }

    @Test
    void existsReturnsTrueForMatchAndFalseForNoMatch() {
        User u = persist("exists-1@b.c");

        boolean present = Model.query(User.class)
            .where(EMAIL.eq("exists-1@b.c"))
            .exists();
        boolean absent = Model.query(User.class)
            .where(EMAIL.eq("exists-nope@b.c"))
            .exists();

        assertThat(present).isTrue();
        assertThat(absent).isFalse();
    }
}
