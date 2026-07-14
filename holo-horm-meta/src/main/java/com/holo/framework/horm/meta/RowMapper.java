package com.holo.framework.horm.meta;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Functional interface for mapping a {@link ResultSet} row to a domain object.
 * Located alongside {@link Mapper} — {@code Mapper<T>} is a specialized
 * RowMapper bound to a specific entity type via APT-generated metadata.
 *
 * <p>Usage with {@link com.holo.framework.horm.core.Repository#rawQuery} or
 * {@link com.holo.framework.horm.core.Horm#rawSql}:
 * <pre>{@code
 * List<UserSummary> summaries = Horm.rawSql().query(
 *     "SELECT dept, COUNT(*) AS cnt FROM users GROUP BY dept",
 *     rs -> new UserSummary(rs.getString(1), rs.getLong(2))
 * );
 * }</pre>
 */
@FunctionalInterface
public interface RowMapper<R> {
    R map(ResultSet rs) throws SQLException;
}
