package com.holo.framework.horm.meta.annotation;

/**
 * Strategies for generating entity primary key values.
 *
 * @see Id#strategy()
 * @see GeneratedValue#strategy()
 */
public enum GenerationType {

    /**
     * Database auto-increment / identity column. The framework reads the
     * generated value back after INSERT (e.g. via {@code Statement.RETURN_GENERATED_KEYS}).
     */
    IDENTITY,

    /**
     * Framework selects an appropriate strategy based on the data source
     * capabilities (e.g. IDENTITY for MySQL, SEQUENCE for PostgreSQL).
     */
    AUTO,

    /**
     * Database sequence. Requires {@code sequenceName} on {@link Id} or
     * {@code generator} on {@link GeneratedValue}. Supported from M2.
     */
    SEQUENCE,

    /**
     * Separate generator table holding per-entity counters. Portable across
     * data sources but slower than {@link #SEQUENCE}. Supported from M2.
     */
    TABLE,

    /**
     * Client-side generated UUID (string form). Suitable for distributed
     * systems and data sources without native auto-increment support.
     */
    UUID
}
