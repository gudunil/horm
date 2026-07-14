package com.holo.framework.horm.core.query;

import com.holo.framework.horm.meta.RowMapper;

import java.util.List;
import java.util.Optional;

/**
 * Raw SQL escape hatch not bound to a specific entity type.
 * Obtained via {@link com.holo.framework.horm.core.Horm#rawSql()} or
 * {@link com.holo.framework.horm.core.Horm#rawSql(String)}.
 *
 * <p>Does not participate in cache chain (no APT metadata for CacheKey).
 *
 * <pre>{@code
 * List<UserSummary> summaries = Horm.rawSql().query(
 *     "SELECT dept, COUNT(*) AS cnt FROM users GROUP BY dept",
 *     rs -> new UserSummary(rs.getString(1), rs.getLong(2))
 * );
 * }</pre>
 */
public interface RawSql {

    /**
     * Execute a SELECT query and map each row via the provided mapper.
     *
     * @param sql      SQL query text with {@code ?} placeholders
     * @param mapper   row mapper for result rows
     * @param bindings parameter values for the placeholders
     * @return list of mapped objects
     */
    <R> List<R> query(String sql, RowMapper<R> mapper, Object... bindings);

    /**
     * Execute a SELECT query and return the first mapped row, or empty.
     *
     * @param sql      SQL query text with {@code ?} placeholders
     * @param mapper   row mapper for result rows
     * @param bindings parameter values for the placeholders
     * @return optional first mapped object
     */
    <R> Optional<R> queryOne(String sql, RowMapper<R> mapper, Object... bindings);

    /**
     * Execute a DML statement (INSERT/UPDATE/DELETE) and return the affected row count.
     *
     * @param sql      SQL text with {@code ?} placeholders
     * @param bindings parameter values for the placeholders
     * @return number of affected rows
     */
    long update(String sql, Object... bindings);
}
