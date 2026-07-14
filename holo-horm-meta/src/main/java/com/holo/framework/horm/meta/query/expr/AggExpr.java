package com.holo.framework.horm.meta.query.expr;

import com.holo.framework.horm.meta.query.TypedField;

/**
 * Aggregate expression (SUM / AVG / MAX / MIN / COUNT).
 *
 * @param <T> aggregate return type
 */
public interface AggExpr<T> extends Expr<T> {

    /** The aggregate function type. */
    FunctionType functionType();

    /** The target field, or {@code null} for COUNT(*). */
    TypedField<?, ?> target();
}
