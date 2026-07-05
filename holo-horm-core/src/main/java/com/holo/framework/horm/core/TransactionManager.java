package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.annotation.Isolation;
import com.holo.framework.horm.meta.annotation.Propagation;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thread-local transaction manager for HORM.
 *
 * <p>Manages a per-thread stack of {@link TransactionStatus} entries. Each
 * entry holds a JDBC {@link Connection} bound to the transaction scope.
 * When a transaction is active, {@link #currentConnection(HormContext)}
 * returns the thread-bound connection; otherwise it falls back to the
 * context's default connection.
 *
 * <p>Supported propagation behaviors (M4):
 * <ul>
 *   <li>{@link Propagation#REQUIRED} — join an existing transaction or
 *       start a new one</li>
 *   <li>{@link Propagation#REQUIRES_NEW} — always start a new independent
 *       transaction, suspending any existing one</li>
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

    private static final ThreadLocal<Deque<TransactionStatus>> TRANSACTION_STACK =
        ThreadLocal.withInitial(ArrayDeque::new);

    private TransactionManager() {
    }

    /**
     * Returns {@code true} if the current thread has an active transaction.
     */
    public static boolean isActive() {
        Deque<TransactionStatus> stack = TRANSACTION_STACK.get();
        return !stack.isEmpty();
    }

    /**
     * Returns the connection bound to the current transaction, or falls
     * back to the context's default connection when no transaction is
     * active.
     *
     * <p>This is the method that {@link JdbcRepository} and
     * {@link com.holo.framework.horm.core.query.QueryImpl} should use
     * instead of {@code ctx.connection()} directly.
     */
    public static Connection currentConnection(HormContext ctx) {
        Deque<TransactionStatus> stack = TRANSACTION_STACK.get();
        if (!stack.isEmpty()) {
            return stack.peek().connection();
        }
        return ctx.connection();
    }

    /**
     * Begins a new transaction according to the given definition.
     *
     * @param ctx  the current HormContext
     * @param def  the transaction definition (propagation, isolation, etc.)
     * @return the status of the newly begun transaction
     */
    public static TransactionStatus begin(HormContext ctx, TransactionDefinition def) {
        Deque<TransactionStatus> stack = TRANSACTION_STACK.get();
        Propagation propagation = def.propagation();

        switch (propagation) {
            case REQUIRED: {
                if (!stack.isEmpty()) {
                    // Join existing transaction
                    TransactionStatus existing = stack.peek();
                    stack.push(new TransactionStatus(
                        existing.connection(), false, null));
                    return stack.peek();
                }
                // Start new transaction
                Connection conn = newConnection(ctx, def);
                stack.push(new TransactionStatus(conn, true, null));
                return stack.peek();
            }
            case REQUIRES_NEW: {
                // Suspend current transaction if one exists
                TransactionStatus suspended = stack.isEmpty() ? null : stack.pop();
                Connection conn = newConnection(ctx, def);
                stack.push(new TransactionStatus(conn, true, suspended));
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
        }
    }

    /**
     * Rolls back the transaction represented by the given status.
     *
     * <p>Only rolls back if {@code status.isNewTransaction()} is true;
     * otherwise the rollback is deferred to the outer transaction.
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
        }
    }

    /**
     * Executes the given action within a transaction.
     *
     * <p>Uses default transaction definition (REQUIRED propagation,
     * DEFAULT isolation).
     */
    public static void execute(HormContext ctx, Runnable action) {
        execute(ctx, TransactionDefinition.builder().build(), action);
    }

    /**
     * Executes the given action within a transaction using the specified
     * propagation behavior.
     */
    public static void execute(HormContext ctx, Propagation propagation,
                              Runnable action) {
        execute(ctx, TransactionDefinition.builder()
            .propagation(propagation).build(), action);
    }

    /**
     * Executes the given action within a transaction using the given
     * definition. Commits on success; rolls back on exception.
     */
    public static void execute(HormContext ctx, TransactionDefinition def,
                              Runnable action) {
        TransactionStatus status = begin(ctx, def);
        try {
            action.run();
            commit(status);
        } catch (Throwable ex) {
            rollback(status);
            throw ex;
        } finally {
            popAndResume(status);
        }
    }

    /**
     * Executes the given callable within a transaction and returns its
     * result. Uses default transaction definition.
     */
    public static <T> T execute(HormContext ctx, java.util.concurrent.Callable<T> action) {
        return execute(ctx, TransactionDefinition.builder().build(), action);
    }

    /**
     * Executes the given callable within a transaction using the specified
     * propagation behavior and returns its result.
     */
    public static <T> T execute(HormContext ctx, Propagation propagation,
                               java.util.concurrent.Callable<T> action) {
        return execute(ctx, TransactionDefinition.builder()
            .propagation(propagation).build(), action);
    }

    /**
     * Executes the given callable within a transaction using the given
     * definition. Commits on success; rolls back on exception.
     */
    public static <T> T execute(HormContext ctx, TransactionDefinition def,
                               java.util.concurrent.Callable<T> action) {
        TransactionStatus status = begin(ctx, def);
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
            popAndResume(status);
        }
    }

    /**
     * Clears the transaction stack for the current thread. Used for
     * testing cleanup.
     */
    public static void clear() {
        TRANSACTION_STACK.remove();
    }

    // --- Internal helpers ---

    private static Connection newConnection(HormContext ctx, TransactionDefinition def) {
        try {
            DataSourceProvider provider = ctx.dataSourceProvider();
            Connection conn = provider != null
                ? provider.getConnection()
                : ctx.connection();
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
            }
        }
    }

    /**
     * Pops the current transaction status from the stack and resumes
     * any suspended transaction (for REQUIRES_NEW).
     */
    private static void popAndResume(TransactionStatus status) {
        Deque<TransactionStatus> stack = TRANSACTION_STACK.get();
        if (!stack.isEmpty() && stack.peek() == status) {
            stack.pop();
        }
        // Resume suspended transaction if any
        if (status.suspended() != null) {
            stack.push(status.suspended());
        }
        // Clean up ThreadLocal if stack is empty
        if (stack.isEmpty()) {
            TRANSACTION_STACK.remove();
        }
    }
}
