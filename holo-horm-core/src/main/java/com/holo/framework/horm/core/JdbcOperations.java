package com.holo.framework.horm.core;

import com.holo.framework.horm.core.dialect.Dialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Internal template helpers that encapsulate the repetitive JDBC
 * {@code Connection → PreparedStatement → ResultSet → catch → finally}
 * boilerplate found across {@link JdbcRepository} and the query
 * implementations in {@code com.holo.framework.horm.core.query}.
 *
 * <p>Each template acquires a connection via
 * {@link TransactionManager#currentConnection}, opens a
 * {@link PreparedStatement}, delegates the SQL-specific work to a
 * functional-interface callback, and guarantees connection release in a
 * {@code finally} block — matching the resource-management contract that
 * HORM enforces everywhere. {@link SQLException}s are uniformly wrapped in
 * {@link HormException} so callers stay block-free.
 *
 * <p><strong>Internal API.</strong> Public for cross-package reuse by
 * {@code com.holo.framework.horm.core.query} implementations; not part of
 * the stable HORM contract and may change between releases.
 */
public final class JdbcOperations {

    private JdbcOperations() {}

    /** Handles a {@link ResultSet} cursor and returns the materialized result. */
    @FunctionalInterface
    public interface ResultSetHandler<T> {
        T handle(ResultSet rs) throws SQLException;
    }

    /** Consumes a generated primary key value returned by an INSERT. */
    @FunctionalInterface
    public interface GeneratedKeyHandler {
        void handle(long generatedKey) throws SQLException;
    }

    /**
     * Executes a SELECT and lets {@code handler} materialize the result.
     * The handler is invoked once with the open {@link ResultSet}; it is
     * responsible for cursor movement (e.g. {@code rs.next()}).
     */
    public static <T> T query(HormContext ctx, String dataSourceName, String sql,
                              List<Object> bindings, ResultSetHandler<T> handler,
                              String errorContext) {
        Connection conn = TransactionManager.currentConnection(ctx, dataSourceName);
        RuntimeException failure = null;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, bindings);
            try (ResultSet rs = ps.executeQuery()) {
                return handler.handle(rs);
            }
        } catch (SQLException e) {
            failure = new HormException("Failed to " + errorContext + ": " + e.getMessage(), e);
            throw failure;
        } finally {
            releaseConnection(ctx, dataSourceName, conn, failure);
        }
    }

    /**
     * Executes an UPDATE/DELETE and returns the affected row count.
     */
    public static int update(HormContext ctx, String dataSourceName, String sql,
                             List<Object> bindings, String errorContext) {
        Connection conn = TransactionManager.currentConnection(ctx, dataSourceName);
        RuntimeException failure = null;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, bindings);
            return ps.executeUpdate();
        } catch (SQLException e) {
            failure = new HormException("Failed to " + errorContext + ": " + e.getMessage(), e);
            throw failure;
        } finally {
            releaseConnection(ctx, dataSourceName, conn, failure);
        }
    }

    /**
     * Executes an INSERT with {@link Statement#RETURN_GENERATED_KEYS} and
     * forwards the first generated key (if any) to {@code keyHandler}.
     */
    public static void insert(HormContext ctx, String dataSourceName, String sql,
                              List<Object> bindings, GeneratedKeyHandler keyHandler,
                              String errorContext) {
        Connection conn = TransactionManager.currentConnection(ctx, dataSourceName);
        RuntimeException failure = null;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindParams(ps, bindings);
            ps.executeUpdate();
            if (keyHandler != null) {
                try (ResultSet genKeys = ps.getGeneratedKeys()) {
                    if (genKeys.next()) {
                        keyHandler.handle(genKeys.getLong(1));
                    }
                }
            }
        } catch (SQLException e) {
            failure = new HormException("Failed to " + errorContext + ": " + e.getMessage(), e);
            throw failure;
        } finally {
            releaseConnection(ctx, dataSourceName, conn, failure);
        }
    }

    /**
     * Executes a batch INSERT with {@link Statement#RETURN_GENERATED_KEYS}
     * and forwards each generated key to {@code keyHandler} in insertion
     * order. Uses {@link PreparedStatement#addBatch()} /
     * {@link PreparedStatement#executeBatch()} to reduce network round-trips.
     *
     * <p><strong>Database Compatibility: Generated Key Ordering.</strong>
     * This method assumes that {@link PreparedStatement#getGeneratedKeys()}
     * returns generated keys in the same order as the rows were added via
     * {@link PreparedStatement#addBatch()}. This behavior is followed by
     * most major JDBC drivers:
     * <ul>
     *   <li><strong>MySQL:</strong> ✅ Supported (Connector/J 5.1+)</li>
     *   <li><strong>PostgreSQL:</strong> ✅ Supported (pgjdbc 42.x+)</li>
     *   <li><strong>H2:</strong> ✅ Supported (1.4+)</li>
     *   <li><strong>Oracle:</strong> ⚠️ May vary by driver version; test before use</li>
     *   <li><strong>SQL Server:</strong> ⚠️ jTDS driver may not preserve order; test before use</li>
     * </ul>
     *
     * <p>If you are using a database driver that does not guarantee generated
     * key ordering, consider one of these alternatives:
     * <ul>
     *   <li>Use single-row {@link #insert} operations (slower but safer)</li>
     *   <li>Manually set primary keys before batch insert (if your schema allows)</li>
     *   <li>Implement a database-specific batch strategy that queries generated
     *       keys after batch execution using database-specific SQL</li>
     * </ul>
     *
     * <p><strong>Error Handling.</strong> This method validates that:
     * <ul>
     *   <li>The number of generated keys exactly matches the batch size</li>
     *   <li>No row in the batch failed to execute (checked via
     *       {@link Statement#EXECUTE_FAILED} in the batch result array)</li>
     * </ul>
     *
     * @param ctx            the runtime context
     * @param dataSourceName the target datasource name
     * @param sql            the INSERT statement (with {@code ?} placeholders)
     * @param batchBindings  per-row bind values; one list per entity
     * @param keyHandler     receives each generated key in insertion order;
     *                       must handle keys sequentially and not skip any
     * @param errorContext   human-readable context for error messages
     * @throws HormException if any batch row failed to execute, or if the
     *                       generated key count does not match the batch size
     */
    public static void batchInsert(HormContext ctx, String dataSourceName, String sql,
                                   List<List<Object>> batchBindings,
                                   GeneratedKeyHandler keyHandler,
                                   String errorContext) {
        if (batchBindings == null || batchBindings.isEmpty()) {
            return;
        }
        Dialect dialect = ctx.dialect(dataSourceName);
        if (keyHandler != null && !dialect.supportsBatchInsertGeneratedKeysInOrder()) {
            throw new HormException("Failed to " + errorContext
                + ": batch insert with generated keys is not supported by dialect " + dialect.name()
                + " because generated key ordering cannot be guaranteed");
        }
        Connection conn = TransactionManager.currentConnection(ctx, dataSourceName);
        RuntimeException failure = null;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (List<Object> bindings : batchBindings) {
                bindParams(ps, bindings);
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            for (int count : counts) {
                if (count == Statement.EXECUTE_FAILED) {
                    failure = new HormException("Failed to " + errorContext + ": batch row failed");
                    throw failure;
                }
            }
            try (ResultSet genKeys = ps.getGeneratedKeys()) {
                if (keyHandler == null) {
                    // No generated keys expected (e.g. MANUAL id strategy).
                    return;
                }
                int keyIndex = 0;
                while (genKeys.next()) {
                    if (keyIndex >= batchBindings.size()) {
                        failure = new HormException("Failed to " + errorContext
                            + ": generated key count (" + (keyIndex + 1) + ") exceeds batch size ("
                            + batchBindings.size() + ")");
                        throw failure;
                    }
                    keyHandler.handle(genKeys.getLong(1));
                    keyIndex++;
                }
                if (keyIndex != batchBindings.size()) {
                    failure = new HormException("Failed to " + errorContext
                        + ": generated key count (" + keyIndex + ") does not match batch size ("
                        + batchBindings.size() + ")");
                    throw failure;
                }
            }
        } catch (SQLException e) {
            failure = new HormException("Failed to " + errorContext + ": " + e.getMessage(), e);
            throw failure;
        } finally {
            releaseConnection(ctx, dataSourceName, conn, failure);
        }
    }

    /** Binds {@code bindings} to {@code ps} starting at index 1, in order. */
    public static void bindParams(PreparedStatement ps, List<Object> bindings) throws SQLException {
        int i = 1;
        for (Object b : bindings) {
            SqlBinding.bindParam(ps, i++, b);
        }
    }

    /**
     * Releases {@code conn} back to the transaction manager. If release fails
     * and {@code failure} is non-null, the release exception is added as a
     * suppressed exception to {@code failure} so that the original error is not
     * lost. If there is no prior failure, the release exception is propagated.
     */
    private static void releaseConnection(HormContext ctx, String dataSourceName,
                                          Connection conn, RuntimeException failure) {
        try {
            TransactionManager.releaseConnection(ctx, dataSourceName, conn);
        } catch (RuntimeException e) {
            if (failure != null) {
                failure.addSuppressed(e);
            } else {
                throw e;
            }
        }
    }
}
