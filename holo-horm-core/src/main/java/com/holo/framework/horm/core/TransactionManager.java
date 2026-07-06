package com.holo.framework.horm.core;

import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import com.holo.framework.horm.meta.annotation.Isolation;
import com.holo.framework.horm.meta.annotation.Propagation;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Thread-local transaction manager for HORM.
 *
 * <p>Manages per-thread, per-datasource stacks of {@link TransactionStatus} entries.
 * Each entry holds a JDBC {@link Connection} bound to the transaction scope.
 * When a transaction is active, {@link #currentConnection(HormContext, String)}
 * returns the thread-bound connection for the specified datasource; otherwise
 * it falls back to the context's connection for that datasource.
 *
 * <p>M5 introduces multi-datasource support. Each datasource maintains its own
 * independent transaction stack. Cross-datasource transactions are not supported
 * (no XA); each datasource commits/rollbacks independently.
 *
 * <p>Supported propagation behaviors (M4):
 * <ul>
 *   <li>{@link Propagation#REQUIRED} — join an existing transaction for the
 *       same datasource, or start a new one</li>
 *   <li>{@link Propagation#REQUIRES_NEW} — always start a new independent
 *       transaction, suspending any existing one for the same datasource</li>
 * </ul>
 *
 * <p>Typical usage through {@link Horm#tx}:
 * <pre>{@code
 * Horm.tx(() -> {
 *     user.save();
 *     order.save();
 * });
 * }</pre>
 */
public final class TransactionManager {

    private static final ThreadLocal<Map<String, Deque<TransactionStatus>>> TRANSACTION_STACKS =
        ThreadLocal.withInitial(HashMap::new);

    private TransactionManager() {
    }

    /**
     * Returns {@code true} if the current thread has an active transaction
     * for the default datasource.
     */
    public static boolean isActive() {
        return isActive(DataSourceRegistry.DEFAULT_NAME);
    }

    /**
     * Returns {@code true} if the current thread has an active transaction
     * for the specified datasource.
     *
     * @param dataSourceName the datasource name
     */
    public static boolean isActive(String dataSourceName) {
        Map<String, Deque<TransactionStatus>> stacks = TRANSACTION_STACKS.get();
        Deque<TransactionStatus> stack = stacks.get(dataSourceName);
        return stack != null && !stack.isEmpty();
    }

    /**
     * Returns the connection bound to the current transaction for the default
     * datasource, or falls back to the context's default connection when no
     * transaction is active.
     *
     * <p>This is the method that {@link JdbcRepository} and
     * {@link com.holo.framework.horm.core.query.QueryImpl} should use
     * instead of {@code ctx.connection()} directly.
     */
    public static Connection currentConnection(HormContext ctx) {
        return currentConnection(ctx, DataSourceRegistry.DEFAULT_NAME);
    }

    /**
     * Returns the connection bound to the current transaction for the specified
     * datasource, or falls back to the context's connection for that datasource
     * when no transaction is active.
     *
     * @param ctx            the current HormContext
     * @param dataSourceName the datasource name
     */
    public static Connection currentConnection(HormContext ctx, String dataSourceName) {
        Map<String, Deque<TransactionStatus>> stacks = TRANSACTION_STACKS.get();
        Deque<TransactionStatus> stack = stacks.get(dataSourceName);
        if (stack != null && !stack.isEmpty()) {
            return stack.peek().connection();
        }
        // No active transaction for this datasource; get connection from context
        DataSourceProvider provider = ctx.dataSourceRegistry().resolve(dataSourceName);
        try {
            return provider.getConnection();
        } catch (SQLException e) {
            throw new TransactionException(
                "Failed to obtain connection from datasource: " + dataSourceName, e);
        }
    }

    /**
     * Begins a new transaction for the default datasource according to the
     * given definition.
     *
     * @param ctx  the current HormContext
     * @param def  the transaction definition (propagation, isolation, etc.)
     * @return the status of the newly begun transaction
     */
    public static TransactionStatus begin(HormContext ctx, TransactionDefinition def) {
        return begin(ctx, DataSourceRegistry.DEFAULT_NAME, def);
    }

    /**
     * Begins a new transaction for the specified datasource according to the
     * given definition.
     *
     * @param ctx            the current HormContext
     * @param dataSourceName the datasource name
     * @param def            the transaction definition (propagation, isolation, etc.)
     * @return the status of the newly begun transaction
     */
    public static TransactionStatus begin(HormContext ctx, String dataSourceName,
                                          TransactionDefinition def) {
        Map<String, Deque<TransactionStatus>> stacks = TRANSACTION_STACKS.get();
        Deque<TransactionStatus> stack = stacks.computeIfAbsent(dataSourceName,
            k -> new ArrayDeque<>());
        Propagation propagation = def.propagation();

        DataSourceProvider provider = ctx.dataSourceRegistry().resolve(dataSourceName);
        switch (propagation) {
            case REQUIRED: {
                if (!stack.isEmpty()) {
                    // Join existing transaction for this datasource
                    TransactionStatus existing = stack.peek();
                    stack.push(new TransactionStatus(
                        existing.connection(), false, null, provider, dataSourceName));
                    return stack.peek();
                }
                // Start new transaction
                Connection conn = newConnection(provider, def);
                stack.push(new TransactionStatus(conn, true, null, provider, dataSourceName));
                return stack.peek();
            }
            case REQUIRES_NEW: {
                // Suspend current transaction for this datasource if one exists
                TransactionStatus suspended = stack.isEmpty() ? null : stack.pop();
                Connection conn = newConnection(provider, def);
                stack.push(new TransactionStatus(conn, true, suspended, provider, dataSourceName));
                return stack.peek();
            }
            default:
                throw new TransactionException(
                    "Unsupported propagation behavior: " + propagation);
        }
    }

    /**
     * Commits the transaction represented by the given status.
     *
     * <p>Only commits if {@code status.isNewTransaction()} is true;
     * otherwise the commit is deferred to the outer transaction.
     *
     * <p>M6: after a successful commit, all registered {@code afterCommit}
     * callbacks are executed in registration order. Exceptions from callbacks
     * are swallowed and logged (best-effort) so that a failing cache update
     * cannot roll back an already-committed transaction. The
     * {@code afterRollback} callbacks are discarded — the transaction
     * committed, so rollback hooks must not fire.
     *
     * <p>For joined (non-new) transactions, callbacks remain on the status
     * and are transferred to the outer transaction by
     * {@link #popAndResume(String, TransactionStatus)} so they fire when the
     * outer transaction completes.
     */
    public static void commit(TransactionStatus status) {
        if (status.isNewTransaction()) {
            try {
                status.connection().commit();
            } catch (SQLException e) {
                throw new TransactionException("Failed to commit transaction", e);
            } finally {
                cleanup(status);
            }
            // Run afterCommit callbacks (swallow exceptions); discard afterRollback.
            for (Runnable r : status.consumeAfterCommitCallbacks()) {
                try {
                    r.run();
                } catch (Throwable t) {
                    System.err.println("[HORM] afterCommit callback threw: " + t);
                }
            }
            status.consumeAfterRollbackCallbacks();
        }
    }

    /**
     * Rolls back the transaction represented by the given status.
     *
     * <p>Only rolls back if {@code status.isNewTransaction()} is true;
     * otherwise the rollback is deferred to the outer transaction.
     *
     * <p>M6: after rollback, all registered {@code afterRollback} callbacks
     * are executed in registration order (exceptions swallowed and logged).
     * The {@code afterCommit} callbacks are discarded — the transaction
     * rolled back, so commit hooks must not fire.
     */
    public static void rollback(TransactionStatus status) {
        if (status.isNewTransaction()) {
            try {
                status.connection().rollback();
            } catch (SQLException e) {
                throw new TransactionException("Failed to rollback transaction", e);
            } finally {
                cleanup(status);
            }
            // Run afterRollback callbacks (swallow exceptions); discard afterCommit.
            for (Runnable r : status.consumeAfterRollbackCallbacks()) {
                try {
                    r.run();
                } catch (Throwable t) {
                    System.err.println("[HORM] afterRollback callback threw: " + t);
                }
            }
            status.consumeAfterCommitCallbacks();
        }
    }

    /**
     * Registers a callback to execute after the current transaction for the
     * default datasource commits.
     *
     * <p>If no transaction is active for the default datasource, the callback
     * runs immediately (autocommit semantics). This lets repository code call
     * {@code afterCommit} uniformly whether or not the caller wrapped the
     * operation in an explicit {@link #execute(HormContext, Runnable)} block.
     *
     * <p>When called inside a joined (non-new) transaction, the callback is
     * transferred to the outer new transaction on {@code popAndResume} and
     * fires when the outer transaction commits.
     *
     * @param callback the action to run after commit; must not be {@code null}
     */
    public static void afterCommit(Runnable callback) {
        afterCommit(DataSourceRegistry.DEFAULT_NAME, callback);
    }

    /**
     * Registers a callback to execute after the current transaction for the
     * specified datasource commits. See {@link #afterCommit(Runnable)} for
     * semantics.
     *
     * @param dataSourceName the datasource name
     * @param callback       the action to run after commit
     */
    public static void afterCommit(String dataSourceName, Runnable callback) {
        Map<String, Deque<TransactionStatus>> stacks = TRANSACTION_STACKS.get();
        Deque<TransactionStatus> stack = stacks.get(dataSourceName);
        if (stack != null && !stack.isEmpty()) {
            stack.peek().addAfterCommitCallback(callback);
        } else {
            // No active transaction: execute immediately (autocommit path).
            callback.run();
        }
    }

    /**
     * Registers a callback to execute after the current transaction for the
     * default datasource rolls back.
     *
     * <p>If no transaction is active, the callback is discarded (there is
     * nothing to roll back). When called inside a joined transaction, the
     * callback is transferred to the outer new transaction.
     *
     * @param callback the action to run after rollback; must not be {@code null}
     */
    public static void afterRollback(Runnable callback) {
        afterRollback(DataSourceRegistry.DEFAULT_NAME, callback);
    }

    /**
     * Registers a callback to execute after the current transaction for the
     * specified datasource rolls back. See {@link #afterRollback(Runnable)}.
     *
     * @param dataSourceName the datasource name
     * @param callback       the action to run after rollback
     */
    public static void afterRollback(String dataSourceName, Runnable callback) {
        Map<String, Deque<TransactionStatus>> stacks = TRANSACTION_STACKS.get();
        Deque<TransactionStatus> stack = stacks.get(dataSourceName);
        if (stack != null && !stack.isEmpty()) {
            stack.peek().addAfterRollbackCallback(callback);
        }
        // No active transaction: discard (nothing to roll back).
    }

    /**
     * Executes the given action within a transaction for the default datasource.
     *
     * <p>Uses default transaction definition (REQUIRED propagation,
     * DEFAULT isolation).
     */
    public static void execute(HormContext ctx, Runnable action) {
        execute(ctx, DataSourceRegistry.DEFAULT_NAME, TransactionDefinition.builder().build(), action);
    }

    /**
     * Executes the given action within a transaction for the default datasource
     * using the specified propagation behavior.
     */
    public static void execute(HormContext ctx, Propagation propagation,
                              Runnable action) {
        execute(ctx, DataSourceRegistry.DEFAULT_NAME, TransactionDefinition.builder()
            .propagation(propagation).build(), action);
    }

    /**
     * Executes the given action within a transaction for the specified datasource.
     *
     * @param ctx            the current HormContext
     * @param dataSourceName the datasource name
     * @param action         the action to execute
     */
    public static void execute(HormContext ctx, String dataSourceName, Runnable action) {
        execute(ctx, dataSourceName, TransactionDefinition.builder().build(), action);
    }

    /**
     * Executes the given action within a transaction for the specified datasource
     * using the given definition. Commits on success; rolls back on exception.
     *
     * @param ctx            the current HormContext
     * @param dataSourceName the datasource name
     * @param def            the transaction definition
     * @param action         the action to execute
     */
    public static void execute(HormContext ctx, String dataSourceName,
                              TransactionDefinition def, Runnable action) {
        TransactionStatus status = begin(ctx, dataSourceName, def);
        try {
            action.run();
            commit(status);
        } catch (Throwable ex) {
            rollback(status);
            throw ex;
        } finally {
            popAndResume(dataSourceName, status);
        }
    }

    /**
     * Executes the given action within a transaction for the default datasource
     * using the given definition. Commits on success; rolls back on exception.
     */
    public static void execute(HormContext ctx, TransactionDefinition def,
                              Runnable action) {
        execute(ctx, DataSourceRegistry.DEFAULT_NAME, def, action);
    }

    /**
     * Executes the given callable within a transaction for the default datasource
     * and returns its result. Uses default transaction definition.
     */
    public static <T> T execute(HormContext ctx, java.util.concurrent.Callable<T> action) {
        return execute(ctx, DataSourceRegistry.DEFAULT_NAME, TransactionDefinition.builder().build(), action);
    }

    /**
     * Executes the given callable within a transaction for the default datasource
     * using the specified propagation behavior and returns its result.
     */
    public static <T> T execute(HormContext ctx, Propagation propagation,
                               java.util.concurrent.Callable<T> action) {
        return execute(ctx, DataSourceRegistry.DEFAULT_NAME, TransactionDefinition.builder()
            .propagation(propagation).build(), action);
    }

    /**
     * Executes the given callable within a transaction for the specified datasource
     * and returns its result. Uses default transaction definition.
     *
     * @param ctx            the current HormContext
     * @param dataSourceName the datasource name
     * @param action         the callable to execute
     * @return the result of the callable
     */
    public static <T> T execute(HormContext ctx, String dataSourceName,
                               java.util.concurrent.Callable<T> action) {
        return execute(ctx, dataSourceName, TransactionDefinition.builder().build(), action);
    }

    /**
     * Executes the given callable within a transaction for the specified datasource
     * using the given definition. Commits on success; rolls back on exception.
     *
     * @param ctx            the current HormContext
     * @param dataSourceName the datasource name
     * @param def            the transaction definition
     * @param action         the callable to execute
     * @return the result of the callable
     */
    public static <T> T execute(HormContext ctx, String dataSourceName,
                               TransactionDefinition def,
                               java.util.concurrent.Callable<T> action) {
        TransactionStatus status = begin(ctx, dataSourceName, def);
        try {
            T result = action.call();
            commit(status);
            return result;
        } catch (Throwable ex) {
            rollback(status);
            if (ex instanceof RuntimeException re) throw re;
            if (ex instanceof Error err) throw err;
            throw new TransactionException("Transaction action threw checked exception", ex);
        } finally {
            popAndResume(dataSourceName, status);
        }
    }

    /**
     * Executes the given callable within a transaction for the default datasource
     * using the given definition. Commits on success; rolls back on exception.
     */
    public static <T> T execute(HormContext ctx, TransactionDefinition def,
                               java.util.concurrent.Callable<T> action) {
        return execute(ctx, DataSourceRegistry.DEFAULT_NAME, def, action);
    }

    /**
     * Clears all transaction stacks for the current thread. Used for
     * testing cleanup.
     */
    public static void clear() {
        TRANSACTION_STACKS.remove();
    }

    // --- Internal helpers ---

    private static Connection newConnection(DataSourceProvider provider, TransactionDefinition def) {
        try {
            Connection conn = provider.getConnection();
            conn.setAutoCommit(false);

            if (def.isolation() != Isolation.DEFAULT) {
                conn.setTransactionIsolation(def.isolation().level());
            }
            if (def.readOnly()) {
                conn.setReadOnly(true);
            }

            return conn;
        } catch (SQLException e) {
            throw new TransactionException("Failed to begin transaction", e);
        }
    }

    private static void cleanup(TransactionStatus status) {
        if (status.isNewTransaction()) {
            try {
                status.connection().setAutoCommit(true);
            } catch (SQLException e) {
                // Best-effort; connection may already be closed
            } finally {
                status.releaseConnection();
            }
        }
    }

    /**
     * Releases {@code connection} to the default datasource's provider when no
     * transaction is active for that datasource. This prevents connection leaks
     * from short-lived, non-transactional repository/query operations when a
     * real {@link DataSourceProvider} is installed.
     */
    public static void releaseConnection(HormContext ctx, Connection connection) {
        releaseConnection(ctx, DataSourceRegistry.DEFAULT_NAME, connection);
    }

    /**
     * Releases {@code connection} to the specified datasource's provider when no
     * transaction is active for that datasource.
     *
     * @param ctx            the current HormContext
     * @param dataSourceName the datasource name
     * @param connection     the connection to release
     */
    public static void releaseConnection(HormContext ctx, String dataSourceName,
                                        Connection connection) {
        if (!isActive(dataSourceName)) {
            ctx.releaseConnection(dataSourceName, connection);
        }
    }

    /**
     * Pops the current transaction status from the stack for the specified
     * datasource and resumes any suspended transaction (for REQUIRES_NEW).
     *
     * <p>M6: when popping a joined (non-new) transaction, its unconsumed
     * callbacks are transferred to the new stack top (the outer transaction)
     * so they fire when the outer transaction commits or rolls back. For a
     * new transaction the callbacks have already been consumed by
     * {@link #commit}/{@link #rollback}, so there is nothing to transfer.
     *
     * <p>Package-private for use by {@link TransactionInterceptor}.
     */
    static void popAndResume(String dataSourceName, TransactionStatus status) {
        Map<String, Deque<TransactionStatus>> stacks = TRANSACTION_STACKS.get();
        Deque<TransactionStatus> stack = stacks.get(dataSourceName);
        if (stack != null && !stack.isEmpty() && stack.peek() == status) {
            stack.pop();
        }
        // Resume suspended transaction if any
        if (status.suspended() != null) {
            if (stack == null) {
                stack = stacks.computeIfAbsent(dataSourceName, k -> new ArrayDeque<>());
            }
            stack.push(status.suspended());
        }
        // Transfer unconsumed callbacks to the new top (outer transaction).
        // For a new transaction this is a no-op (callbacks already consumed).
        // For a joined transaction this propagates them to the outer scope.
        if (stack != null && !stack.isEmpty()) {
            status.transferCallbacksTo(stack.peek());
        }
        // Clean up ThreadLocal if all stacks are empty
        if (stack != null && stack.isEmpty()) {
            stacks.remove(dataSourceName);
        }
        if (stacks.isEmpty()) {
            TRANSACTION_STACKS.remove();
        }
    }
}
