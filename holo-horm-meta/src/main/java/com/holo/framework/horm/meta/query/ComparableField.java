package com.holo.framework.horm.meta.query;

/**
 * {@link TypedField} whose value type is {@link Comparable}, exposing the
 * comparison condition builders ({@code gt}/{@code lt}/{@code ge}/{@code le}/
 * {@code between}). Fields without ordering semantics (e.g. {@code Boolean},
 * {@code Enum}) stay on {@code TypedField} and never see these methods, which
 * keeps the type hierarchy honest at compile time.
 *
 * @param <E> entity type
 * @param <T> comparable field value type
 */
public interface ComparableField<E, T extends Comparable<T>> extends TypedField<E, T> {

    default Condition gt(T value) {
        return Conditions.gt(this, value);
    }

    default Condition lt(T value) {
        return Conditions.lt(this, value);
    }

    default Condition ge(T value) {
        return Conditions.ge(this, value);
    }

    default Condition le(T value) {
        return Conditions.le(this, value);
    }

    default Condition between(T low, T high) {
        return Conditions.between(this, low, high);
    }
}
