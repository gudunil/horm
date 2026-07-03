package com.holo.framework.horm.meta.query;

/**
 * Type-safe field reference for the HORM query DSL.
 *
 * <p>One {@code TypedField} constant is generated per field on the
 * {@code XxxQueryMeta} class at compile time:
 * <pre>{@code
 * public static final LongField<User> ID = LongField.of(User.class, "id", "id");
 * }</pre>
 *
 * <p>M1-4 ships a minimal contract (entity type / name / column / type).
 * Condition builders ({@code eq}/{@code ne}/{@code gt}/{@code lt}/{@code like}/
 * {@code in}/{@code between}/{@code asc}/{@code desc}) arrive in M2.
 *
 * @param <E> entity type
 * @param <T> field value type
 */
public interface TypedField<E, T> {
    Class<E> entityType();
    String name();
    String column();
    Class<T> type();
}
