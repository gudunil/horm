package com.holo.framework.horm.meta.query.expr;

import java.util.List;

/**
 * Scalar function expression (UPPER / LOWER / DATE_FORMAT / YEAR / ...).
 *
 * <p>Typically produced by {@link Functions} factory methods. Most scalar
 * functions return {@link ComparableExpr} rather than {@code FuncExpr}
 * directly, so that HAVING-clause chaining (e.g.
 * {@code Functions.year(field).gt(2026)}) works seamlessly.
 * {@code FuncExpr} is retained for internal type discrimination via
 * {@code instanceof}.
 *
 * @param <T> function return type
 */
public interface FuncExpr<T> extends Expr<T> {

    /** The scalar function type. */
    FunctionType functionType();

    /** The function arguments. */
    List<Expr<?>> arguments();
}
