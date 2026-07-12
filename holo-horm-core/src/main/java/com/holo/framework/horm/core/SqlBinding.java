package com.holo.framework.horm.core;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/**
 * Shared helper for binding Java values to JDBC {@link PreparedStatement}
 * placeholders.
 *
 * <p>Normalizes temporal types before delegating to {@link PreparedStatement#setObject}
 * so that the write path is consistent with {@link com.holo.framework.horm.meta.Row#getInstant}.
 */
public final class SqlBinding {

    private SqlBinding() {}

    /**
     * Binds a single value to the statement at the given index.
     *
     * <p>Temporal types are converted to {@link Timestamp} to avoid driver-specific
     * behaviour when passing JDK 8 date-time objects directly through setObject.
     *
     * @param ps    the prepared statement
     * @param index the 1-based parameter index
     * @param value the value to bind
     * @throws SQLException if binding fails
     */
    public static void bindParam(PreparedStatement ps, int index, Object value) throws SQLException {
        if (value instanceof Instant instant) {
            ps.setObject(index, Timestamp.from(instant));
        } else if (value instanceof LocalDateTime ldt) {
            ps.setObject(index, Timestamp.valueOf(ldt));
        } else if (value instanceof OffsetDateTime odt) {
            ps.setObject(index, Timestamp.from(odt.toInstant()));
        } else if (value instanceof ZonedDateTime zdt) {
            ps.setObject(index, Timestamp.from(zdt.toInstant()));
        } else {
            ps.setObject(index, value);
        }
    }
}
