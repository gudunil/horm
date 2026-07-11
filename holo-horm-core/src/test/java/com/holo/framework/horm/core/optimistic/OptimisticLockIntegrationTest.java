package com.holo.framework.horm.core.optimistic;

import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.OptimisticLockException;
import com.holo.framework.horm.core.TransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OptimisticLockIntegrationTest {

    private static final String H2_URL = "jdbc:h2:mem:horm;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private Connection conn;

    @BeforeAll
    void setup() throws SQLException {
        EntityMetaRegistry.reload();

        conn = DriverManager.getConnection(H2_URL);
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS versioned_items (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "  name VARCHAR(128) NOT NULL, " +
                "  version BIGINT DEFAULT 0" +
                ")");
        }

        Horm.install(new HormContext((DataSourceProvider) () -> {
            Connection c = DriverManager.getConnection(H2_URL);
            c.setAutoCommit(true);
            return c;
        }));
    }

    @AfterAll
    void teardown() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE versioned_items");
        }
        conn.close();
    }

    @AfterEach
    void cleanup() throws SQLException {
        TransactionManager.clear();
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM versioned_items");
        }
    }

    @Test
    void insertSetsVersionToNull() throws SQLException {
        VersionedItem item = new VersionedItem();
        item.setName("test-item");
        item.save();

        assertThat(item.getId()).isNotNull();
        // After INSERT, version is not set by HORM (insertable=false)
        // The database DEFAULT 0 applies
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT version FROM versioned_items WHERE id = " + item.getId())) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("version")).isEqualTo(0);
        }
    }

    @Test
    void updateIncrementsVersion() throws SQLException {
        VersionedItem item = new VersionedItem();
        item.setName("test-item");
        item.save();

        // Read back to get the DB-defaulted version=0
        VersionedItem loaded = Model.find(VersionedItem.class, item.getId());
        assertThat(loaded.getVersion()).isEqualTo(0L);

        // Update should increment version
        loaded.setName("updated-item");
        loaded.save();
        assertThat(loaded.getVersion()).isEqualTo(1L);

        // Verify in DB
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT version, name FROM versioned_items WHERE id = " + item.getId())) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("version")).isEqualTo(1);
            assertThat(rs.getString("name")).isEqualTo("updated-item");
        }

        // Second update increments again
        loaded.setName("updated-again");
        loaded.save();
        assertThat(loaded.getVersion()).isEqualTo(2L);
    }

    @Test
    void updateWithStaleVersionThrowsOptimisticLockException() throws SQLException {
        VersionedItem item = new VersionedItem();
        item.setName("test-item");
        item.save();

        // Simulate two concurrent reads
        VersionedItem reader1 = Model.find(VersionedItem.class, item.getId());
        VersionedItem reader2 = Model.find(VersionedItem.class, item.getId());

        // Reader1 updates successfully (version 0 -> 1)
        reader1.setName("reader1-update");
        reader1.save();
        assertThat(reader1.getVersion()).isEqualTo(1L);

        // Reader2 has stale version=0, should fail
        reader2.setName("reader2-update");
        assertThatThrownBy(() -> reader2.save())
            .isInstanceOf(OptimisticLockException.class)
            .hasMessageContaining("Optimistic lock failed");

        // Verify the data was not corrupted
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT name, version FROM versioned_items WHERE id = " + item.getId())) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("name")).isEqualTo("reader1-update");
            assertThat(rs.getLong("version")).isEqualTo(1);
        }
    }

    @Test
    void deleteWithStaleVersionThrowsOptimisticLockException() throws SQLException {
        VersionedItem item = new VersionedItem();
        item.setName("test-item");
        item.save();

        // Read the item (version=0)
        VersionedItem reader1 = Model.find(VersionedItem.class, item.getId());

        // Directly update version in DB to simulate concurrent modification
        try (Statement st = conn.createStatement()) {
            st.execute("UPDATE versioned_items SET version = 1 WHERE id = " + item.getId());
        }

        // Reader1 still has version=0, delete should fail
        assertThatThrownBy(() -> reader1.delete())
            .isInstanceOf(OptimisticLockException.class)
            .hasMessageContaining("Optimistic lock failed on delete");

        // Verify row still exists
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT COUNT(*) FROM versioned_items WHERE id = " + item.getId())) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(1);
        }
    }

    @Test
    void deleteWithCurrentVersionSucceeds() throws SQLException {
        VersionedItem item = new VersionedItem();
        item.setName("test-item");
        item.save();

        // Read back, then update to get version=1
        VersionedItem loaded = Model.find(VersionedItem.class, item.getId());
        loaded.setName("updated");
        loaded.save();
        assertThat(loaded.getVersion()).isEqualTo(1L);

        // Delete with correct version should succeed
        loaded.delete();

        // Verify row is gone
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT COUNT(*) FROM versioned_items WHERE id = " + item.getId())) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(0);
        }
    }
}
