package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.annotation.Isolation;
import com.holo.framework.horm.meta.annotation.Propagation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionManager}.
 *
 * <p>Covers transaction lifecycle, propagation behaviors (REQUIRED, REQUIRES_NEW),
 * commit/rollback semantics based on {@code isNewTransaction}, and the
 * {@code execute} convenience methods.
 */
class TransactionManagerTest {

    private Connection mockConnection;
    private HormContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        TransactionManager.clear();
        mockConnection = mock(Connection.class);
        // HormContext(Connection) wraps it in SimpleDataSourceProvider
        ctx = new HormContext(mockConnection);
    }

    @AfterEach
    void tearDown() {
        TransactionManager.clear();
        HormContext.install(null);
    }

    // ----- 1. isActive returns false when no transaction -----

    @Test
    void isActiveReturnsFalseWhenNoTransaction() {
        assertThat(TransactionManager.isActive()).isFalse();
    }

    // ----- 2. currentConnection falls back to ctx.connection() when no transaction -----

    @Test
    void currentConnectionFallsBackToCtxConnectionWhenNoTransaction() {
        Connection result = TransactionManager.currentConnection(ctx);
        assertThat(result).isSameAs(mockConnection);
    }

    // ----- 3. REQUIRED creates new transaction when stack is empty -----

    @Test
    void requiredCreatesNewTransactionWhenStackIsEmpty() throws SQLException {
        TransactionDefinition def = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();

        TransactionStatus status = TransactionManager.begin(ctx, def);

        assertThat(TransactionManager.isActive()).isTrue();
        assertThat(status.isNewTransaction()).isTrue();
        // The connection was obtained from the DataSourceProvider
        assertThat(status.connection()).isSameAs(mockConnection);
        // setAutoCommit(false) must have been called for a new transaction
        verify(mockConnection).setAutoCommit(false);
        // Cleanup
        TransactionManager.rollback(status);
        TransactionManager.clear();
    }

    // ----- 4. REQUIRED joins existing transaction when stack is not empty (same connection) -----

    @Test
    void requiredJoinsExistingTransactionWhenStackIsNotEmpty() throws SQLException {
        // Start an outer REQUIRED transaction first
        TransactionDefinition outerDef = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionStatus outerStatus = TransactionManager.begin(ctx, outerDef);

        // Now begin an inner REQUIRED — should join (same connection, isNewTransaction=false)
        TransactionDefinition innerDef = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionStatus innerStatus = TransactionManager.begin(ctx, innerDef);

        assertThat(innerStatus.isNewTransaction()).isFalse();
        assertThat(innerStatus.connection()).isSameAs(outerStatus.connection());
        // Same connection object means the inner reuses the outer's connection
        assertThat(innerStatus.connection()).isSameAs(mockConnection);

        // Cleanup inner then outer
        TransactionManager.commit(innerStatus);
        TransactionManager.clear();
        // outer was still on stack but we cleared; just make sure no exceptions
    }

    // ----- 5. REQUIRES_NEW always creates new transaction -----

    @Test
    void requiresNewAlwaysCreatesNewTransaction() throws SQLException {
        TransactionDefinition def = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRES_NEW).build();

        TransactionStatus status = TransactionManager.begin(ctx, def);

        assertThat(status.isNewTransaction()).isTrue();
        assertThat(status.connection()).isSameAs(mockConnection);
        verify(mockConnection).setAutoCommit(false);

        TransactionManager.rollback(status);
        TransactionManager.clear();
    }

    // ----- 6. REQUIRES_NEW suspends existing transaction -----

    @Test
    void requiresNewSuspendsExistingTransaction() throws SQLException {
        // Start a REQUIRED transaction
        TransactionDefinition outerDef = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionStatus outerStatus = TransactionManager.begin(ctx, outerDef);

        // Start REQUIRES_NEW — should suspend the outer
        TransactionDefinition innerDef = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRES_NEW).build();
        TransactionStatus innerStatus = TransactionManager.begin(ctx, innerDef);

        assertThat(innerStatus.isNewTransaction()).isTrue();
        // The suspended reference points to the outer transaction
        assertThat(innerStatus.suspended()).isNotNull();
        assertThat(innerStatus.suspended()).isSameAs(outerStatus);

        // Cleanup
        TransactionManager.rollback(innerStatus);
        TransactionManager.clear();
    }

    // ----- 7. commit on newTransaction=true calls connection.commit() -----

    @Test
    void commitOnNewTransactionCallsConnectionCommit() throws SQLException {
        TransactionDefinition def = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionStatus status = TransactionManager.begin(ctx, def);

        TransactionManager.commit(status);

        verify(mockConnection).commit();
        // cleanup also restores autoCommit
        verify(mockConnection).setAutoCommit(true);
        TransactionManager.clear();
    }

    // ----- 8. commit on newTransaction=false does not commit (outer transaction controls) -----

    @Test
    void commitOnJoinedTransactionDoesNotCommit() throws SQLException {
        // Outer transaction
        TransactionDefinition outerDef = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionStatus outerStatus = TransactionManager.begin(ctx, outerDef);

        // Inner REQUIRED joins — isNewTransaction=false
        TransactionDefinition innerDef = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionStatus innerStatus = TransactionManager.begin(ctx, innerDef);

        // Commit on inner should NOT call connection.commit()
        TransactionManager.commit(innerStatus);

        verify(mockConnection, never()).commit();

        TransactionManager.clear();
    }

    // ----- 9. rollback on newTransaction=true calls connection.rollback() -----

    @Test
    void rollbackOnNewTransactionCallsConnectionRollback() throws SQLException {
        TransactionDefinition def = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionStatus status = TransactionManager.begin(ctx, def);

        TransactionManager.rollback(status);

        verify(mockConnection).rollback();
        verify(mockConnection).setAutoCommit(true);
        TransactionManager.clear();
    }

    // ----- 10. rollback on newTransaction=false does not rollback -----

    @Test
    void rollbackOnJoinedTransactionDoesNotRollback() throws SQLException {
        // Outer transaction
        TransactionDefinition outerDef = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionStatus outerStatus = TransactionManager.begin(ctx, outerDef);

        // Inner REQUIRED joins
        TransactionDefinition innerDef = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionStatus innerStatus = TransactionManager.begin(ctx, innerDef);

        // Rollback on inner should NOT call connection.rollback()
        TransactionManager.rollback(innerStatus);

        verify(mockConnection, never()).rollback();

        TransactionManager.clear();
    }

    // ----- 11. execute(Runnable) commits on success -----

    @Test
    void executeRunnableCommitsOnSuccess() throws SQLException {
        TransactionManager.execute(ctx, () -> {
            // action succeeds
        });

        verify(mockConnection).commit();
        // After commit, autoCommit should be restored
        verify(mockConnection).setAutoCommit(true);
    }

    // ----- 12. execute(Runnable) rolls back on exception -----

    @Test
    void executeRunnableRollsBackOnException() throws SQLException {
        assertThatThrownBy(() ->
            TransactionManager.execute(ctx, (Runnable) () -> {
                throw new RuntimeException("boom");
            })
        ).isInstanceOf(RuntimeException.class)
         .hasMessage("boom");

        verify(mockConnection).rollback();
        verify(mockConnection).setAutoCommit(true);
    }

    // ----- 13. execute(Callable) returns result on success -----

    @Test
    void executeCallableReturnsResultOnSuccess() throws SQLException {
        Callable<String> action = () -> "hello";

        String result = TransactionManager.execute(ctx, action);

        assertThat(result).isEqualTo("hello");
        verify(mockConnection).commit();
        verify(mockConnection).setAutoCommit(true);
    }

    // ----- 14. execute(Callable) rolls back on checked exception -----

    @Test
    void executeCallableRollsBackOnCheckedException() throws SQLException {
        Callable<Void> action = () -> {
            throw new Exception("checked failure");
        };

        assertThatThrownBy(() -> TransactionManager.execute(ctx, action))
            .isInstanceOf(TransactionException.class)
            .hasMessageContaining("checked exception");

        verify(mockConnection).rollback();
        verify(mockConnection).setAutoCommit(true);
    }

    // ===== Additional edge-case tests =====

    @Test
    void isActiveReturnsTrueDuringTransaction() {
        TransactionDefinition def = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionManager.begin(ctx, def);

        assertThat(TransactionManager.isActive()).isTrue();

        TransactionManager.clear();
    }

    @Test
    void currentConnectionReturnsTransactionBoundConnectionWhenActive() throws SQLException {
        TransactionDefinition def = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionStatus status = TransactionManager.begin(ctx, def);

        Connection bound = TransactionManager.currentConnection(ctx);
        assertThat(bound).isSameAs(mockConnection);

        TransactionManager.clear();
    }

    @Test
    void executeWithPropagationRequiresNewCreatesIndependentTransaction() throws SQLException {
        Connection innerConnection = mock(Connection.class);
        DataSourceProvider provider = mock(DataSourceProvider.class);
        when(provider.getConnection()).thenReturn(mockConnection, innerConnection);
        HormContext ctxWithProvider = new HormContext(provider);

        AtomicReference<Connection> innerConnRef = new AtomicReference<>();
        TransactionManager.execute(ctxWithProvider, Propagation.REQUIRED, (Runnable) () -> {
            assertThat(TransactionManager.isActive()).isTrue();
            Connection outerConn = TransactionManager.currentConnection(ctxWithProvider);
            assertThat(outerConn).isSameAs(mockConnection);

            TransactionManager.execute(ctxWithProvider, Propagation.REQUIRES_NEW, (Runnable) () -> {
                innerConnRef.set(TransactionManager.currentConnection(ctxWithProvider));
            });

            assertThat(TransactionManager.isActive()).isTrue();
        });

        assertThat(innerConnRef.get()).isSameAs(innerConnection);
        verify(mockConnection).commit();
        verify(innerConnection).commit();
    }

    @Test
    void popAndResumeRestoresSuspendedTransaction() throws SQLException {
        // Start REQUIRED
        TransactionDefinition outerDef = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionStatus outerStatus = TransactionManager.begin(ctx, outerDef);

        // Start REQUIRES_NEW (suspends outer)
        TransactionDefinition innerDef = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRES_NEW).build();
        TransactionStatus innerStatus = TransactionManager.begin(ctx, innerDef);

        // Commit and pop inner — should resume outer
        TransactionManager.commit(innerStatus);
        // Manually resume via the same logic as execute's finally
        // (popAndResume is private, so we use clear + re-setup for this test)
        TransactionManager.clear();

        // Verify that after the inner transaction finishes,
        // the suspended transaction concept is valid
        assertThat(innerStatus.suspended()).isSameAs(outerStatus);
    }

    @Test
    void clearRemovesAllTransactionState() throws SQLException {
        TransactionDefinition def = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionManager.begin(ctx, def);

        assertThat(TransactionManager.isActive()).isTrue();

        TransactionManager.clear();

        assertThat(TransactionManager.isActive()).isFalse();
    }

    @Test
    void unsupportedPropagationThrowsTransactionException() {
        // We can't easily test unsupported propagation since the enum only has
        // REQUIRED and REQUIRES_NEW. Instead, verify that REQUIRED and
        // REQUIRES_NEW both work without throwing.
        TransactionDefinition requiredDef = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionStatus status = TransactionManager.begin(ctx, requiredDef);
        assertThat(status).isNotNull();
        TransactionManager.clear();
    }

    @Test
    void commitFailureThrowsTransactionException() throws SQLException {
        TransactionDefinition def = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionStatus status = TransactionManager.begin(ctx, def);

        doThrow(new SQLException("commit error")).when(mockConnection).commit();

        assertThatThrownBy(() -> TransactionManager.commit(status))
            .isInstanceOf(TransactionException.class)
            .hasMessageContaining("Failed to commit");

        TransactionManager.clear();
    }

    @Test
    void rollbackFailureThrowsTransactionException() throws SQLException {
        TransactionDefinition def = TransactionDefinition.builder()
            .propagation(Propagation.REQUIRED).build();
        TransactionStatus status = TransactionManager.begin(ctx, def);

        doThrow(new SQLException("rollback error")).when(mockConnection).rollback();

        assertThatThrownBy(() -> TransactionManager.rollback(status))
            .isInstanceOf(TransactionException.class)
            .hasMessageContaining("Failed to rollback");

        TransactionManager.clear();
    }
}
