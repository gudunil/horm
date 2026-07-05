package com.holo.framework.horm.core.relations;

import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.HormException;
import com.holo.framework.horm.core.Model;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import com.holo.framework.horm.core.relations.generated.OrderQueryMeta;

import static com.holo.framework.horm.core.relations.generated.OrderQueryMeta.PARENT_USERS;
import static com.holo.framework.horm.core.relations.generated.UserWithRelationsQueryMeta.EMAIL;
import static com.holo.framework.horm.core.relations.generated.UserWithRelationsQueryMeta.ID;
import static com.holo.framework.horm.core.relations.generated.UserWithRelationsQueryMeta.ORDERS;
import static com.holo.framework.horm.core.relations.generated.UserWithRelationsQueryMeta.PROFILES;
import static com.holo.framework.horm.core.relations.generated.UserWithRelationsQueryMeta.PRODUCTS;
import static com.holo.framework.horm.core.relations.generated.UserWithRelationsQueryMeta.TAGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration tests for M3 relation mapping against an
 * in-memory H2 database (MODE=MySQL). Exercises all five RelationType
 * fetches, where-on-parent filtering, select projection, and the
 * select-without-id HormException.
 *
 * <p>Uses independent entity fixtures ({@link UserWithRelations},
 * {@link Profile}, {@link Order}, {@link Tag}, {@link Product}) to
 * avoid any regression on M1/M2's {@code User.java}-based tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRelationsTest {

    private Connection conn;

    @BeforeAll
    void setup() throws SQLException {
        EntityMetaRegistry.reload();
        conn = DriverManager.getConnection("jdbc:h2:mem:horm;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE users_with_relations (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "  email VARCHAR(128) NOT NULL" +
                ")");
            st.execute(
                "CREATE TABLE profiles (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "  user_id BIGINT, " +
                "  name VARCHAR(64)" +
                ")");
            st.execute(
                "CREATE TABLE orders (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "  user_id BIGINT, " +
                "  product_id BIGINT, " +
                "  amount DECIMAL(19,2)" +
                ")");
            st.execute(
                "CREATE TABLE products (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "  name VARCHAR(64)" +
                ")");
            st.execute(
                "CREATE TABLE tags (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "  name VARCHAR(64)" +
                ")");
            st.execute(
                "CREATE TABLE user_tags_rel (" +
                "  user_id BIGINT NOT NULL, " +
                "  tag_id BIGINT NOT NULL" +
                ")");
        }
        Horm.install(new HormContext(conn));
    }

    @AfterAll
    void teardown() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE user_tags_rel");
            st.execute("DROP TABLE tags");
            st.execute("DROP TABLE products");
            st.execute("DROP TABLE orders");
            st.execute("DROP TABLE profiles");
            st.execute("DROP TABLE users_with_relations");
        }
        conn.close();
    }

    @BeforeEach
    void resetData() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM user_tags_rel");
            st.execute("DELETE FROM tags");
            st.execute("DELETE FROM products");
            st.execute("DELETE FROM orders");
            st.execute("DELETE FROM profiles");
            st.execute("DELETE FROM users_with_relations");

            st.execute("INSERT INTO users_with_relations (id, email) VALUES (1, 'u1@h')");
            st.execute("INSERT INTO users_with_relations (id, email) VALUES (2, 'u2@h')");

            st.execute("INSERT INTO profiles (id, user_id, name) VALUES (1, 1, 'p1')");

            st.execute("INSERT INTO orders (id, user_id, product_id, amount) VALUES (1, 1, 1, 100)");
            st.execute("INSERT INTO orders (id, user_id, product_id, amount) VALUES (2, 1, 2, 200)");
            st.execute("INSERT INTO orders (id, user_id, product_id, amount) VALUES (3, 2, NULL, 300)");

            st.execute("INSERT INTO products (id, name) VALUES (1, 'prod1')");
            st.execute("INSERT INTO products (id, name) VALUES (2, 'prod2')");

            st.execute("INSERT INTO tags (id, name) VALUES (1, 't1')");
            st.execute("INSERT INTO tags (id, name) VALUES (2, 't2')");

            st.execute("INSERT INTO user_tags_rel (user_id, tag_id) VALUES (1, 1)");
            st.execute("INSERT INTO user_tags_rel (user_id, tag_id) VALUES (1, 2)");
        }
    }

    @Test
    void fetchHasManyPopulatesOrdersList() {
        List<UserWithRelations> result = Model.query(UserWithRelations.class)
            .fetch(ORDERS)
            .where(ID.eq(1L))
            .list();

        assertThat(result).hasSize(1);
        UserWithRelations user1 = result.get(0);
        assertThat(user1.getId()).isEqualTo(1L);
        assertThat(user1.getOrders())
            .extracting(Order::getId)
            .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void fetchHasOnePopulatesProfile() {
        List<UserWithRelations> result = Model.query(UserWithRelations.class)
            .fetch(PROFILES)
            .where(ID.eq(1L))
            .list();

        assertThat(result).hasSize(1);
        UserWithRelations user1 = result.get(0);
        assertThat(user1.getProfiles()).hasSize(1);
        assertThat(user1.getProfiles().get(0).getId()).isEqualTo(1L);
        assertThat(user1.getProfiles().get(0).getName()).isEqualTo("p1");
    }

    @Test
    void fetchBelongsToPopulatesParentUsers() {
        List<Order> result = Model.query(Order.class)
            .fetch(PARENT_USERS)
            .orderBy(OrderQueryMeta.ID, com.holo.framework.horm.core.query.Order.ASC)
            .list();

        assertThat(result).hasSize(3);
        Order order1 = result.stream().filter(o -> o.getId() == 1L).findFirst().orElseThrow();
        assertThat(order1.getParentUsers()).hasSize(1);
        assertThat(order1.getParentUsers().get(0).getId()).isEqualTo(1L);

        Order order3 = result.stream().filter(o -> o.getId() == 3L).findFirst().orElseThrow();
        assertThat(order3.getParentUsers()).hasSize(1);
        assertThat(order3.getParentUsers().get(0).getId()).isEqualTo(2L);
    }

    @Test
    void fetchHasAndBelongsToManyPopulatesTags() {
        List<UserWithRelations> result = Model.query(UserWithRelations.class)
            .fetch(TAGS)
            .where(ID.eq(1L))
            .list();

        assertThat(result).hasSize(1);
        UserWithRelations user1 = result.get(0);
        assertThat(user1.getTags())
            .extracting(Tag::getId)
            .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void fetchHasManyThroughPopulatesProducts() {
        List<UserWithRelations> result = Model.query(UserWithRelations.class)
            .fetch(PRODUCTS)
            .where(ID.eq(1L))
            .list();

        assertThat(result).hasSize(1);
        UserWithRelations user1 = result.get(0);
        assertThat(user1.getProducts())
            .extracting(Product::getId)
            .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void leftJoinWithWhereOnParentFiltersResults() {
        List<UserWithRelations> result = Model.query(UserWithRelations.class)
            .fetch(ORDERS)
            .where(ID.eq(2L))
            .list();

        assertThat(result).hasSize(1);
        UserWithRelations user2 = result.get(0);
        assertThat(user2.getId()).isEqualTo(2L);
        assertThat(user2.getOrders()).extracting(Order::getId).containsExactly(3L);
    }

    @Test
    void selectProjectionReturnsIdOnlyWithEmailNull() {
        List<UserWithRelations> result = Model.query(UserWithRelations.class)
            .select(ID)
            .orderBy(ID, com.holo.framework.horm.core.query.Order.ASC)
            .list();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getEmail()).isNull();
        assertThat(result.get(1).getId()).isEqualTo(2L);
        assertThat(result.get(1).getEmail()).isNull();
    }

    @Test
    void selectWithoutIdThrowsHormException() {
        assertThatThrownBy(() -> Model.query(UserWithRelations.class).select(EMAIL).list())
            .isInstanceOf(HormException.class)
            .hasMessageContaining("select projection must include id field");
    }
}
