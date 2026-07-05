package com.holo.framework.horm.core.transaction;

import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.TransactionDefinition;
import com.holo.framework.horm.core.TransactionManager;
import com.holo.framework.horm.meta.annotation.Isolation;
import com.holo.framework.horm.meta.annotation.Propagation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2 in-memory integration tests for HORM transaction management.
 *
 * <p>Verifies commit/rollback behavior, REQUIRED and REQUIRES_NEW propagation,
 * callable return values, and isolation level support using actual SQL against
 * an H2 database.
 *
 * <p>Uses a {@link DataSourceProvider} that creates fresh H2 connections rather
 * than the single-connection {@code SimpleDataSourceProvider}. This is required
 * for {@link Propagation#REQUIRES_NEW} to obtain an independent connection so
 * that inner-transaction commits are not visible on the outer transaction's
 * connection until the outer commits (and vice versa for rollbacks).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionIntegrationTest {

    private static final String H2_URL = "jdbc:h2:mem:horm;MODE=MySQL;DB_CLOSE_DELAY=-1";

    /** Direct connection used for DDL, data verification, and cleanup. */
    private Connection conn;

    @BeforeAll
    void setup() throws SQLException {
        EntityMetaRegistry.reload();

        conn = DriverManager.getConnection(H2_URL);
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS accounts (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "  name VARCHAR(128) NOT NULL, " +
                "  balance DECIMAL(19,2) NOT NULL" +
                ")");
        }

        // DataSourceProvider that opens a fresh H2 connection on each call.
        // This enables REQUIRES_NEW to obtain an independent physical
        // connection, which SimpleDataSourceProvider (always returning the
        // same Connection) cannot provide.
        Horm.install(new HormContext((DataSourceProvider) () -> {
            Connection c = DriverManager.getConnection(H2_URL);
            c.setAutoCommit(true);
            return c;
        }));
    }

    @AfterAll
    void teardown() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE accounts");
        }
        conn.close();
    }

    @AfterEach
    void cleanup() throws SQLException {
        TransactionManager.clear();
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM accounts");
        }
    }

    // ===== Helper methods =====

    private long countAccounts() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM accounts")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private boolean accountExists(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM accounts WHERE name = ? LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Account newAccount(String name, String balance) {
        Account a = new Account();
        a.setName(name);
        a.setBalance(new BigDecimal(balance));
        return a;
    }

    // ===== Test cases =====

    @Test
    void txCommit_persistsBothAccounts() throws SQLException {
        Account a1 = newAccount("alice", "1000.00");
        Account a2 = newAccount("bob", "2000.00");

        Horm.tx(() -> {
            a1.save();
            a2.save();
        });

        assertThat(a1.getId()).isNotNull();
        assertThat(a2.getId()).isNotNull();
        assertThat(countAccounts()).isEqualTo(2);
        assertThat(accountExists("alice")).isTrue();
        assertThat(accountExists("bob")).isTrue();
    }

    @Test
    void txRollback_discardsAllChanges() {
        assertThatThrownBy(() -> Horm.tx(() -> {
            newAccount("charlie", "500.00").save();
            newAccount("dave", "750.00").save();
            throw new RuntimeException("Force rollback");
        })).isInstanceOf(RuntimeException.class)
          .hasMessage("Force rollback");

        assertThat(countAccountsSafe()).isEqualTo(0);
    }

    @Test
    void requiredNested_innerJoinsOuter_bothSucceed() throws SQLException {
        Horm.tx(() -> {
            newAccount("outer", "100.00").save();

            Horm.tx(Propagation.REQUIRED, () -> {
                newAccount("inner", "200.00").save();
            });
        });

        assertThat(countAccounts()).isEqualTo(2);
        assertThat(accountExists("outer")).isTrue();
        assertThat(accountExists("inner")).isTrue();
    }

    @Test
    void requiredNestedRollback_innerExceptionRollsBackOuter() {
        assertThatThrownBy(() -> Horm.tx(() -> {
            newAccount("outer-before", "100.00").save();

            Horm.tx(Propagation.REQUIRED, () -> {
                newAccount("inner-will-fail", "200.00").save();
                throw new RuntimeException("Inner failure");
            });
        })).isInstanceOf(RuntimeException.class)
          .hasMessage("Inner failure");

        // Both outer and inner changes are rolled back because REQUIRED
        // shares the same physical connection; the rollback on the outer
        // transaction undoes everything.
        assertThat(countAccountsSafe()).isEqualTo(0);
    }

    @Test
    void requiresNewIsolation_innerCommitsIndependently() throws SQLException {
        assertThatThrownBy(() -> Horm.tx(() -> {
            newAccount("outer-will-rollback", "100.00").save();

            Horm.tx(Propagation.REQUIRES_NEW, () -> {
                newAccount("inner-committed", "200.00").save();
            });

            // Force outer to roll back after inner has already committed
            throw new RuntimeException("Outer rollback");
        })).isInstanceOf(RuntimeException.class)
          .hasMessage("Outer rollback");

        // Inner transaction committed on a separate connection, so its
        // data persists despite the outer rollback.
        assertThat(countAccounts()).isEqualTo(1);
        assertThat(accountExists("inner-committed")).isTrue();
        assertThat(accountExists("outer-will-rollback")).isFalse();
    }

    @Test
    void txCallable_returnsValue() throws SQLException {
        BigDecimal total = Horm.tx(() -> {
            Account a1 = newAccount("caller1", "300.00");
            a1.save();
            Account a2 = newAccount("caller2", "700.00");
            a2.save();
            return a1.getBalance().add(a2.getBalance());
        });

        assertThat(total).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(countAccounts()).isEqualTo(2);
    }

    @Test
    void txWithIsolation_readCommitted() throws Exception {
        TransactionDefinition def = TransactionDefinition.builder()
            .isolation(Isolation.READ_COMMITTED)
            .build();

        int isolation = TransactionManager.execute(HormContext.current(), def, () -> {
            return TransactionManager.currentConnection(HormContext.current())
                .getTransactionIsolation();
        });

        assertThat(isolation).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
    }

    /**
     * Count accounts without throwing a checked exception, for use in
     * assertion chains after rollback tests.
     */
    private long countAccountsSafe() {
        try {
            return countAccounts();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
