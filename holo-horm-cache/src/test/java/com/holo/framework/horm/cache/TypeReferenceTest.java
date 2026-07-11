package com.holo.framework.horm.cache;

import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TypeReference}.
 *
 * <p>Coverage focus:
 * <ul>
 *   <li>anonymous-subclass instantiation captures the bare class type
 *       (e.g. {@code TypeReference<String>} → {@code String.class});</li>
 *   <li>parameterised types are preserved at runtime (the whole point of
 *       the super-type-token pattern);</li>
 *   <li>nested generics ({@code Map<String, List<User>>}) retain their
 *       full signature;</li>
 *   <li>misuse without an anonymous subclass throws a clear
 *       {@link IllegalStateException}.</li>
 * </ul>
 *
 * <p>Test-only types {@link User} and {@link Account} are nested at the
 * bottom of this class so the generic-capture assertions are
 * self-documenting without polluting the production package.
 */
class TypeReferenceTest {

    // ── bare class capture ─────────────────────────────────────────────

    @Test
    void capturesBareClassType() {
        TypeReference<String> ref = new TypeReference<>() {};
        Type type = ref.getType();

        assertThat(type).isEqualTo(String.class);
    }

    @Test
    void capturesCustomClassType() {
        TypeReference<User> ref = new TypeReference<>() {};
        Type type = ref.getType();

        assertThat(type).isEqualTo(User.class);
    }

    @Test
    void capturesPrimitiveWrapperType() {
        TypeReference<Long> ref = new TypeReference<>() {};
        assertThat(ref.getType()).isEqualTo(Long.class);
    }

    // ── parameterised type preservation ────────────────────────────────

    @Test
    void capturesParameterisedListType() {
        // The whole reason TypeReference exists: List<User> erases to List
        // at runtime, but the anonymous subclass retains the parameter.
        TypeReference<List<User>> ref = new TypeReference<>() {};
        Type type = ref.getType();

        assertThat(type).isInstanceOf(ParameterizedType.class);
        ParameterizedType pt = (ParameterizedType) type;
        assertThat(pt.getRawType()).isEqualTo(List.class);
        assertThat(pt.getActualTypeArguments()).containsExactly(User.class);
    }

    @Test
    void capturesParameterisedMapType() {
        TypeReference<Map<String, Account>> ref = new TypeReference<>() {};
        Type type = ref.getType();

        assertThat(type).isInstanceOf(ParameterizedType.class);
        ParameterizedType pt = (ParameterizedType) type;
        assertThat(pt.getRawType()).isEqualTo(Map.class);
        assertThat(pt.getActualTypeArguments()).containsExactly(String.class, Account.class);
    }

    @Test
    void capturesNestedParameterisedType() {
        // Map<String, List<User>> — the inner List<User> must also be a
        // ParameterizedType, not erased to List.
        TypeReference<Map<String, List<User>>> ref = new TypeReference<>() {};
        Type type = ref.getType();

        assertThat(type).isInstanceOf(ParameterizedType.class);
        ParameterizedType outer = (ParameterizedType) type;
        assertThat(outer.getRawType()).isEqualTo(Map.class);
        Type inner = outer.getActualTypeArguments()[1];
        assertThat(inner).isInstanceOf(ParameterizedType.class);
        ParameterizedType innerPt = (ParameterizedType) inner;
        assertThat(innerPt.getRawType()).isEqualTo(List.class);
        assertThat(innerPt.getActualTypeArguments()).containsExactly(User.class);
    }

    @Test
    void distinctInstancesCaptureEqualTypes() {
        // Two separate anonymous instances capturing the same parameter
        // must yield equal Type objects, so TypeReference can be used as
        // a lookup key.
        TypeReference<List<User>> a = new TypeReference<>() {};
        TypeReference<List<User>> b = new TypeReference<>() {};

        assertThat(a.getType()).isEqualTo(b.getType());
        assertThat(a.getType().hashCode()).isEqualTo(b.getType().hashCode());
    }

    // ── misuse ─────────────────────────────────────────────────────────

    @Test
    void rawSubclassWithoutParameterThrows() {
        // Extending TypeReference as a raw type (no diamond) breaks the
        // super-type-token contract: getGenericSuperclass() returns the
        // raw Class<TypeReference>, not a ParameterizedType, and the
        // constructor rejects it with a clear message.
        assertThatThrownBy(RawTypeReference::new)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TypeReference");
    }

    // ── test-only fixture types ────────────────────────────────────────

    /** Stand-in for an entity type, used in parameterised-capture assertions. */
    static final class User {
    }

    /** Stand-in for a second entity type, used in nested Map assertions. */
    static final class Account {
    }

    /**
     * A named subclass that extends the raw {@link TypeReference} (no
     * type parameter). This is the misuse pattern the constructor guards
     * against: without a parameterised supertype, no type can be
     * captured. The {@code @SuppressWarnings("rawtypes")} acknowledges
     * the deliberate raw-type usage.
     */
    @SuppressWarnings("rawtypes")
    static final class RawTypeReference extends TypeReference {
        RawTypeReference() {
            super();
        }
    }
}
