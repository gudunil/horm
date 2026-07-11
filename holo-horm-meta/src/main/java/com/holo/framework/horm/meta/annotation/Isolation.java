package com.holo.framework.horm.meta.annotation;

import java.sql.Connection;

/**
 * Transaction isolation levels for HORM transaction management.
 *
 * <p>Maps to {@link Connection} constants. {@link #DEFAULT} uses the
 * underlying data source's default isolation level.
 */
public enum Isolation {

    /** Use the default isolation level of the underlying data source. */
    DEFAULT(-1),

    /** Dirty reads are allowed. */
    READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),

    /** Dirty reads are prevented; non-repeatable reads can occur. */
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),

    /** Dirty reads and non-repeatable reads are prevented; phantom reads can occur. */
    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),

    /** Dirty reads, non-repeatable reads, and phantom reads are prevented. */
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

    private final int level;

    Isolation(int level) {
        this.level = level;
    }

    /** Returns the JDBC {@link Connection} isolation level constant. */
    public int level() {
        return level;
    }
}
