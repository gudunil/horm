package com.holo.framework.horm.core;

/**
 * Unchecked exception thrown by the HORM runtime when a persistence
 * operation fails (e.g. a {@link java.sql.SQLException} escaping
 * {@link JdbcRepository}).
 *
 * <p>Wrapping JDBC checked exceptions in a single {@code RuntimeException}
 * subtype keeps {@code Model} / {@link Horm} call-sites clean while still
 * giving callers a precise type to catch when they need to react to
 * data-source failures.
 */
public class HormException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HormException(String message) {
        super(message);
    }

    public HormException(String message, Throwable cause) {
        super(message, cause);
    }
}
