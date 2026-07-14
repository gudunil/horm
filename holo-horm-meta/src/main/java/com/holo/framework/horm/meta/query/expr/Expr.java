package com.holo.framework.horm.meta.query.expr;

import java.util.List;

/**
 * Renderable SQL expression with typed return value.
 *
 * <p>Like {@link com.holo.framework.horm.meta.query.Condition}, an
 * {@code Expr} carries both the SQL text (with {@code ?} placeholders)
 * and the ordered bindings that fill those placeholders. Unlike
 * {@code Condition}, an {@code Expr} may return any Java type, not just
 * boolean.
 *
 * <p>{@link com.holo.framework.horm.meta.query.TypedField} implements
 * {@code Expr<T>}, so any API accepting {@code Expr<?>} also accepts
 * APT-generated {@code XxxQueryMeta} field constants directly.
 *
 * @param <T> expression return type
 */
public interface Expr<T> {

    /** SQL text with {@code ?} placeholders, e.g. {@code "UPPER(t0.name)"} or {@code "SUM(t0.amount)"}. */
    String sqlFragment();

    /** Bindings for the {@code ?} placeholders, in fragment order. May contain {@code null}. */
    List<Object> bindings();

    /** Expression return type, used by {@link com.holo.framework.horm.meta.Row} typed accessors. */
    Class<T> javaType();

    /** Optional alias for projection column naming. */
    default String alias() { return null; }
}
