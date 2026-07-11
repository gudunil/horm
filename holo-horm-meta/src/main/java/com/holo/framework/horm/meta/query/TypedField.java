package com.holo.framework.horm.meta.query;

import java.util.Collection;

/**
 * Type-safe field reference for the HORM query DSL.
 *
 * <p>One {@code TypedField} constant is generated per field on the
 * {@code XxxQueryMeta} class at compile time:
 * <pre>{@code
 * public static final LongField<User> ID = LongField.of(User.class, "id", "id");
 * }</pre>
 *
 * <p>Default condition builders ({@code eq}/{@code ne}/{@code isNull}/
 * {@code isNotNull}/{@code in}) delegate to {@link Conditions} and return a
 * renderable {@link Condition}. Comparison operators ({@code gt}/{@code lt}/
 * {@code ge}/{@code le}/{@code between}) live on {@link ComparableField} and
 * are only available on fields whose value type is {@link Comparable}.
 *
 * @param <E> entity type
 * @param <T> field value type
 */
public interface TypedField<E, T> {

    Class<E> entityType();

    String name();

    String column();

    Class<T> type();

    default Condition eq(T value) {
        return Conditions.eq(this, value);
    }

    default Condition ne(T value) {
        return Conditions.ne(this, value);
    }

    default Condition isNull() {
        return Conditions.isNull(this);
    }

    default Condition isNotNull() {
        return Conditions.isNotNull(this);
    }

    default Condition in(Collection<T> values) {
        return Conditions.in(this, values);
    }
}
