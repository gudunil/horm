package com.holo.framework.horm.meta;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Type-safe accessor for a single field on an entity, backed by method
 * references rather than reflection.
 *
 * <p>The annotation processor generates one {@code FieldAccessor} constant
 * per field on the {@code XxxMeta} class:
 * <pre>{@code
 * public static final FieldAccessor<User, Long> ID_ACCESSOR =
 *     FieldAccessor.of(User::getId, User::setId);
 * }</pre>
 *
 * <p>JVM {@code invokedynamic} + {@code LambdaMetafactory} lower method
 * references to direct {@code invokevirtual} calls, delivering near-native
 * performance (~1.5 ns/op vs ~8.5 ns/op for cached {@code Method.invoke}).
 *
 * @param <T> entity type
 * @param <V> field value type
 */
@FunctionalInterface
public interface FieldAccessor<T, V> {

    /** Reads the field value from the entity. */
    V get(T entity);

    /**
     * Writes the field value to the entity. Default-implemented as a
     * no-op throw to allow single-method lambdas (method references to
     * getters); generated accessors always override both directions.
     */
    default void set(T entity, V value) {
        throw new UnsupportedOperationException("FieldAccessor.set is not implemented");
    }

    /** Creates a read-write accessor from getter and setter method references. */
    static <T, V> FieldAccessor<T, V> of(Function<T, V> getter, BiConsumer<T, V> setter) {
        return new FieldAccessor<T, V>() {
            @Override public V get(T entity) { return getter.apply(entity); }
            @Override public void set(T entity, V value) { setter.accept(entity, value); }
        };
    }

    /** Creates a read-only accessor (setter throws on write). */
    static <T, V> FieldAccessor<T, V> readOnly(Function<T, V> getter) {
        return entity -> getter.apply(entity);
    }
}
