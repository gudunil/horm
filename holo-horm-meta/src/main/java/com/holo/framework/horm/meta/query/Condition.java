package com.holo.framework.horm.meta.query;

import java.util.List;

/**
 * Renderable SQL fragment produced by {@link Conditions} factories or
 * {@link TypedField} default methods.
 *
 * <p>A {@code Condition} carries both the SQL text (with {@code ?} placeholders)
 * and the ordered bindings that fill those placeholders. Composite conditions
 * ({@link #and}, {@link #or}, {@link #not}) recursively combine fragments and
 * concatenate bindings in the same order they appear in the fragment.
 */
public interface Condition {

    /** SQL text with {@code ?} placeholders, e.g. {@code "email = ?"} or {@code "(a = ?) AND (b > ?)"}. */
    String sqlFragment();

    /** Bindings for the {@code ?} placeholders, in fragment order. May contain {@code null}. */
    List<Object> bindings();

    /** Combine conditions with AND. A single-argument call returns the input unchanged. */
    static Condition and(Condition... conditions) {
        return CompositeCondition.and(conditions);
    }

    /** Combine conditions with OR. A single-argument call returns the input unchanged. */
    static Condition or(Condition... conditions) {
        return CompositeCondition.or(conditions);
    }

    /** Negate a condition, wrapping it in {@code NOT (...)}. */
    static Condition not(Condition condition) {
        return CompositeCondition.not(condition);
    }
}
