package com.holo.framework.horm.core;

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
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, bindings);
            try (ResultSet rs = ps.executeQuery()) {
                return handler.handle(rs);
            }
        } catch (SQLException e) {
            throw new HormException("Failed to " + errorContext, e);
        } finally {
            TransactionManager.releaseConnection(ctx, dataSourceName, conn);
        }
    }

    /**
     * Executes an UPDATE/DELETE and returns the affected row count.
     */
    public static int update(HormContext ctx, String dataSourceName, String sql,
                             List<Object> bindings, String errorContext) {
        Connection conn = TransactionManager.currentConnection(ctx, dataSourceName);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, bindings);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new HormException("Failed to " + errorContext, e);
        } finally {
            TransactionManager.releaseConnection(ctx, dataSourceName, conn);
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
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindParams(ps, bindings);
            ps.executeUpdate();
            try (ResultSet genKeys = ps.getGeneratedKeys()) {
                if (genKeys.next()) {
                    keyHandler.handle(genKeys.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new HormException("Failed to " + errorContext, e);
        } finally {
            TransactionManager.releaseConnection(ctx, dataSourceName, conn);
        }
    }

    /** Binds {@code bindings} to {@code ps} starting at index 1, in order. */
    public static void bindParams(PreparedStatement ps, List<Object> bindings) throws SQLException {
        int i = 1;
        for (Object b : bindings) {
            SqlBinding.bindParam(ps, i++, b);
        }
    }
}
