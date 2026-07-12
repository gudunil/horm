package com.holo.framework.horm.core;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents the state of an active transaction, stored on the
 * {@link TransactionManager}'s ThreadLocal stack.
 *
 * <p>M6 adds post-commit/post-rollback callback queues so that repository
 * code can defer cache mutations until the JDBC commit succeeds (or schedule
 * cleanup on rollback). Callbacks are queued on the innermost active
 * transaction regardless of whether it is new or joined; only the outermost
 * new transaction's commit/rollback triggers them.
 *
 * <p><b>Thread safety.</b> Instances of this class are <b>NOT</b> thread-safe.
 * The callback lists use plain {@link ArrayList} because transaction operations
 * (begin, commit, rollback, callback registration) are expected to occur on a
 * single thread — the thread that owns the transaction via the
 * {@link TransactionManager}'s ThreadLocal. If callback registration from
 * multiple threads is required in the future, the lists should be changed to
 * {@link java.util.concurrent.CopyOnWriteArrayList} or synchronized access
 * should be added.
 */
public final class TransactionStatus {

    private final Connection connection;
    private final boolean newTransaction;
    private final TransactionStatus suspended;
    private final DataSourceProvider connectionProvider;
    private final String dataSourceName;

    // NOTE: These lists are NOT thread-safe. See class-level Javadoc for
    // the thread-confinement rationale. All callback registration and
    // consumption must occur on the transaction-owning thread.
    private List<Runnable> afterCommitCallbacks;
    private List<Runnable> afterRollbackCallbacks;

    /**
     * @param connection       the JDBC connection bound to this transaction
     * @param newTransaction   {@code true} if this is a newly created
     *                         transaction (as opposed to joining an existing one)
     * @param suspended        the previously suspended transaction status
     *                         (for REQUIRES_NEW), or {@code null}
     * @param connectionProvider the provider that supplied the connection,
     *                         used to release it on transaction completion
     * @param dataSourceName   the name of the datasource this transaction belongs to
     */
    public TransactionStatus(Connection connection, boolean newTransaction,
                             TransactionStatus suspended,
                             DataSourceProvider connectionProvider,
                             String dataSourceName) {
        this.connection = connection;
        this.newTransaction = newTransaction;
        this.suspended = suspended;
        this.connectionProvider = connectionProvider;
        this.dataSourceName = dataSourceName;
    }

    public Connection connection() { return connection; }
    public boolean isNewTransaction() { return newTransaction; }

    /** Returns the suspended transaction (for REQUIRES_NEW), or null. */
    public TransactionStatus suspended() { return suspended; }

    /**
     * Registers a callback to execute after the transaction commits.
     * Lazily initialises the callback list to avoid allocating empty lists
     * for transactions that never schedule cache work.
     */
    void addAfterCommitCallback(Runnable callback) {
        if (afterCommitCallbacks == null) {
            afterCommitCallbacks = new ArrayList<>();
        }
        afterCommitCallbacks.add(callback);
    }

    /** Registers a callback to execute after the transaction rolls back. */
    void addAfterRollbackCallback(Runnable callback) {
        if (afterRollbackCallbacks == null) {
            afterRollbackCallbacks = new ArrayList<>();
        }
        afterRollbackCallbacks.add(callback);
    }

    /** Returns an unmodifiable view of the post-commit callbacks (may be empty). */
    List<Runnable> afterCommitCallbacks() {
        return afterCommitCallbacks == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(afterCommitCallbacks);
    }

    /** Returns an unmodifiable view of the post-rollback callbacks (may be empty). */
    List<Runnable> afterRollbackCallbacks() {
        return afterRollbackCallbacks == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(afterRollbackCallbacks);
    }

    /**
     * Returns and clears the post-commit callbacks. Used by
     * {@link TransactionManager#commit} to execute callbacks exactly once
     * after a successful commit, and to discard them on rollback.
     *
     * @return a mutable list of callbacks (possibly empty); never {@code null}
     */
    List<Runnable> consumeAfterCommitCallbacks() {
        List<Runnable> result = afterCommitCallbacks == null
            ? new ArrayList<>() : afterCommitCallbacks;
        afterCommitCallbacks = null;
        return result;
    }

    /**
     * Returns and clears the post-rollback callbacks. Used by
     * {@link TransactionManager#rollback} to execute callbacks exactly once
     * after a rollback, and to discard them on commit.
     *
     * @return a mutable list of callbacks (possibly empty); never {@code null}
     */
    List<Runnable> consumeAfterRollbackCallbacks() {
        List<Runnable> result = afterRollbackCallbacks == null
            ? new ArrayList<>() : afterRollbackCallbacks;
        afterRollbackCallbacks = null;
        return result;
    }

    /**
     * Transfers any unconsumed callbacks to {@code outer}, appending them to
     * the outer transaction's callback queues. Used by
     * {@link TransactionManager}'s {@code popAndResume} when a joined
     * (non-new) transaction completes without triggering commit/rollback —
     * its callbacks must fire when the outer new transaction commits or
     * rolls back.
     *
     * @param outer the outer transaction to receive the callbacks; may be {@code null}
     */
    void transferCallbacksTo(TransactionStatus outer) {
        if (outer == null) {
            return;
        }
        if (afterCommitCallbacks != null) {
            for (Runnable r : afterCommitCallbacks) {
                outer.addAfterCommitCallback(r);
            }
            afterCommitCallbacks = null;
        }
        if (afterRollbackCallbacks != null) {
            for (Runnable r : afterRollbackCallbacks) {
                outer.addAfterRollbackCallback(r);
            }
            afterRollbackCallbacks = null;
        }
    }

    /** Releases the transaction-bound connection to its provider, if any. */
    void releaseConnection() {
        if (connectionProvider != null) {
            connectionProvider.releaseConnection(connection);
        }
    }
}
