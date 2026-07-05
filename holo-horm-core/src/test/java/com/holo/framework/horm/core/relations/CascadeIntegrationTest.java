package com.holo.framework.horm.core.relations;

import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.Model;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 integration tests for M4 cascade persist/remove operations.
 *
 * <p>Uses the existing {@link UserWithRelations}/{@link Profile}/{@link Order}
 * fixtures with {@code CascadeType.ALL} on the parent-side relations.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CascadeIntegrationTest {

    private static final String H2_URL = "jdbc:h2:mem:hormcascade;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private Connection conn;

    @BeforeAll
    void setup() throws SQLException {
        EntityMetaRegistry.reload();
        conn = DriverManager.getConnection(H2_URL);
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS users_with_relations (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "  email VARCHAR(128) NOT NULL" +
                ")");
            st.execute(
                "CREATE TABLE IF NOT EXISTS profiles (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "  user_id BIGINT, " +
                "  name VARCHAR(64)" +
                ")");
            st.execute(
                "CREATE TABLE IF NOT EXISTS orders (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "  user_id BIGINT, " +
                "  product_id BIGINT, " +
                "  amount DECIMAL(19,2)" +
                ")");
        }
        Horm.install(new HormContext(conn));
    }

    @AfterAll
    void teardown() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS orders");
            st.execute("DROP TABLE IF EXISTS profiles");
            st.execute("DROP TABLE IF EXISTS users_with_relations");
        }
        conn.close();
    }

    @BeforeEach
    void resetData() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM orders");
            st.execute("DELETE FROM profiles");
            st.execute("DELETE FROM users_with_relations");
        }
    }

    // ===== Helpers =====

    private long countUsers() throws SQLException {
        return countTable("users_with_relations");
    }

    private long countProfiles() throws SQLException {
        return countTable("profiles");
    }

    private long countOrders() throws SQLException {
        return countTable("orders");
    }

    private long countTable(String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private UserWithRelations newUser(String email) {
        UserWithRelations user = new UserWithRelations();
        user.setEmail(email);
        return user;
    }

    private Order newOrder(String amount) {
        Order order = new Order();
        order.setAmount(new BigDecimal(amount));
        return order;
    }

    private Profile newProfile(String name) {
        Profile profile = new Profile();
        profile.setName(name);
        return profile;
    }

    // ===== Tests =====

    @Test
    void cascadeSave_persistsParentAndChildrenWithForeignKeys() throws SQLException {
        UserWithRelations user = newUser("cascade@h");
        Order order1 = newOrder("100.00");
        Order order2 = newOrder("200.00");
        Profile profile = newProfile("p-cascade");

        user.setOrders(new ArrayList<>(List.of(order1, order2)));
        user.setProfiles(new ArrayList<>(List.of(profile)));

        user.save();

        assertThat(user.getId()).isNotNull();
        assertThat(order1.getId()).isNotNull();
        assertThat(order2.getId()).isNotNull();
        assertThat(profile.getId()).isNotNull();

        assertThat(order1.getUserId()).isEqualTo(user.getId());
        assertThat(order2.getUserId()).isEqualTo(user.getId());
        assertThat(profile.getUserId()).isEqualTo(user.getId());

        assertThat(countUsers()).isEqualTo(1L);
        assertThat(countOrders()).isEqualTo(2L);
        assertThat(countProfiles()).isEqualTo(1L);
    }

    @Test
    void cascadeSaveWith_onlyNamedRelationPersisted() throws SQLException {
        UserWithRelations user = newUser("partial@h");
        Order order = newOrder("50.00");
        Profile profile = newProfile("p-partial");

        user.setOrders(new ArrayList<>(List.of(order)));
        user.setProfiles(new ArrayList<>(List.of(profile)));

        user.saveWith("orders");

        assertThat(user.getId()).isNotNull();
        assertThat(order.getId()).isNotNull();
        assertThat(order.getUserId()).isEqualTo(user.getId());

        assertThat(profile.getId()).isNull();
        assertThat(countUsers()).isEqualTo(1L);
        assertThat(countOrders()).isEqualTo(1L);
        assertThat(countProfiles()).isEqualTo(0L);
    }

    @Test
    void cascadeDelete_removesParentAndChildren() throws SQLException {
        UserWithRelations user = newUser("delete@h");
        Order order = newOrder("75.00");
        Profile profile = newProfile("p-delete");
        user.setOrders(new ArrayList<>(List.of(order)));
        user.setProfiles(new ArrayList<>(List.of(profile)));
        user.save();

        assertThat(countUsers()).isEqualTo(1L);
        assertThat(countOrders()).isEqualTo(1L);
        assertThat(countProfiles()).isEqualTo(1L);

        user.delete();

        assertThat(countUsers()).isEqualTo(0L);
        assertThat(countOrders()).isEqualTo(0L);
        assertThat(countProfiles()).isEqualTo(0L);
    }

    @Test
    void cascadeDeleteWith_onlyNamedRelationRemoved() throws SQLException {
        UserWithRelations user = newUser("deleteWith@h");
        Order order = newOrder("30.00");
        Profile profile = newProfile("p-keep");
        user.setOrders(new ArrayList<>(List.of(order)));
        user.setProfiles(new ArrayList<>(List.of(profile)));
        user.save();

        assertThat(countUsers()).isEqualTo(1L);
        assertThat(countOrders()).isEqualTo(1L);
        assertThat(countProfiles()).isEqualTo(1L);

        user.deleteWith("orders");

        assertThat(countUsers()).isEqualTo(0L);
        assertThat(countOrders()).isEqualTo(0L);
        assertThat(countProfiles()).isEqualTo(1L);
    }
}
