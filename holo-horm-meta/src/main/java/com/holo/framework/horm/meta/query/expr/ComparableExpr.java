package com.holo.framework.horm.meta.query.expr;

import com.holo.framework.horm.meta.query.ComparableField;
import com.holo.framework.horm.meta.query.Condition;
import com.holo.framework.horm.meta.query.Conditions;

/**
 * Expression whose return type is {@link Comparable}, supporting
 * comparison operators via default methods (mirrors
 * {@link ComparableField}).
 *
 * @param <T> comparable return type
 */
public interface ComparableExpr<T extends Comparable<T>> extends Expr<T> {

    default Condition gt(T value) {
        return Conditions.rawWithLeading(sqlFragment() + " > ?", bindings(), value);
    }

    default Condition lt(T value) {
        return Conditions.rawWithLeading(sqlFragment() + " < ?", bindings(), value);
    }

    default Condition ge(T value) {
        return Conditions.rawWithLeading(sqlFragment() + " >= ?", bindings(), value);
    }

    default Condition le(T value) {
        return Conditions.rawWithLeading(sqlFragment() + " <= ?", bindings(), value);
    }

    default Condition between(T low, T high) {
        return Conditions.rawWithLeading(sqlFragment() + " BETWEEN ? AND ?", bindings(), low, high);
    }

    default Condition eq(T value) {
        return Conditions.rawWithLeading(sqlFragment() + " = ?", bindings(), value);
    }

    default Condition ne(T value) {
        return Conditions.rawWithLeading(sqlFragment() + " <> ?", bindings(), value);
    }

    default Condition isNull() {
        return Conditions.rawWithLeading(sqlFragment() + " IS NULL", bindings());
    }

    default Condition isNotNull() {
        return Conditions.rawWithLeading(sqlFragment() + " IS NOT NULL", bindings());
    }
}
