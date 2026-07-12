package com.holo.framework.horm.cache;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Captures a complete generic type signature at compile time.
 *
 * <p>Java erases generic type parameters at runtime, so a cache that
 * stores serialised payloads (e.g. a Redis-backed L2 decoding JSON) has
 * no way to recover the value type from a bare {@code Class<V>} when
 * {@code V} is itself generic (e.g. {@code List<User>}). Subclassing
 * {@code TypeReference} with an anonymous class embeds the parameterised
 * supertype in the class metadata, where reflection can recover it:
 *
 * <pre>{@code
 * TypeReference<List<User>> type = new TypeReference<>() {};
 * Type t = type.getType();  // java.util.List<com.holo...User>
 * }</pre>
 *
 * <p>This is the standard "super type token" pattern popularised by
 * Jackson and Guava. Call sites pass the anonymous instance to
 * {@link Cache#get(Object, TypeReference)}; the implementation invokes
 * {@link #getType()} to drive deserialisation.
 *
 * <p>The class is abstract to force the anonymous-subclass idiom: a
 * direct instantiation ({@code new TypeReference<String>()}) would
 * create a class with no captured parameter, and {@link #getType()}
 * would return the raw {@code Class} rather than a parameterised type.
 *
 * @param <T> the type captured by this reference
 */
public abstract class TypeReference<T> {

    private final Type type;

    /**
     * Captures the parameterised supertype of the concrete anonymous
     * subclass. Subclasses MUST be anonymous ({@code new TypeReference<X>() {}})
     * so that the captured type carries full generic information; named
     * subclasses would erase to {@code TypeReference} itself.
     */
    protected TypeReference() {
        Type superclass = getClass().getGenericSuperclass();
        if (!(superclass instanceof ParameterizedType parameterized)) {
            throw new IllegalStateException(
                "TypeReference must be instantiated as an anonymous class with "
                    + "a type parameter, e.g. new TypeReference<User>(){}; got "
                    + superclass);
        }
        this.type = parameterized.getActualTypeArguments()[0];
    }

    /**
     * Private constructor for simple (non-generic) types.
     * Used by the {@link #of(Class)} factory method.
     */
    private TypeReference(Class<T> type) {
        this.type = type;
    }

    /**
     * Factory method for simple (non-generic) types. Avoids the need for
     * an anonymous subclass when the value type is a plain class:
     *
     * <pre>{@code
     * TypeReference<User> type = TypeReference.of(User.class);
     * // equivalent to: new TypeReference<User>() {}
     * }</pre>
     *
     * <p>For parameterised types (e.g. {@code List<User>}), the anonymous
     * subclass idiom must still be used:
     * {@code new TypeReference<List<User>>() {}}
     *
     * @param type the class representing the value type
     * @param <T>  the value type
     * @return a TypeReference capturing the given class
     */
    public static <T> TypeReference<T> of(Class<T> type) {
        return new StaticTypeReference<>(type);
    }

    /**
     * Returns the captured type. For a bare class parameter (e.g.
     * {@code new TypeReference<User>() {}}), this returns the
     * {@link Class} object for {@code User}. For a parameterised
     * parameter (e.g. {@code new TypeReference<List<User>>() {}}), this
     * returns a {@link ParameterizedType} retaining the full signature.
     *
     * @return the captured type; never {@code null}
     */
    public Type getType() {
        return type;
    }

    private static final class StaticTypeReference<T> extends TypeReference<T> {
        StaticTypeReference(Class<T> type) {
            super(type);
        }
    }
}
