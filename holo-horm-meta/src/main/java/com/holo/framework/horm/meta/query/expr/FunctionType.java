package com.holo.framework.horm.meta.query.expr;

/**
 * Enumeration of SQL function types recognized by the HORM expression DSL.
 * Each value maps to a factory method on {@link Aggregates} or {@link Functions},
 * and is translated to dialect-specific SQL by
 * {@link com.holo.framework.horm.core.dialect.Dialect#functionSql}.
 *
 * <p>{@link #RAW} is the escape-hatch marker; it must never reach
 * {@code Dialect.functionSql()} — raw expressions render directly via
 * {@link Expr#sqlFragment()}.
 */
public enum FunctionType {
    // Aggregate
    COUNT, COUNT_DISTINCT, SUM, AVG, MAX, MIN,
    // String
    UPPER, LOWER, TRIM, SUBSTRING, CONCAT, LENGTH,
    // Math
    ABS, ROUND, FLOOR, CEIL,
    // Date
    NOW, DATE_FORMAT, YEAR, MONTH, DAY,
    // Control flow
    COALESCE, NULLIF,
    // Escape hatch
    RAW
}
