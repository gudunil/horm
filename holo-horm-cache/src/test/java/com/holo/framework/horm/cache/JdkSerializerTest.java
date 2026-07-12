package com.holo.framework.horm.cache;

import org.junit.jupiter.api.Test;

import java.io.NotSerializableException;
import java.io.Serializable;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JdkSerializer}.
 *
 * <p>Coverage focus:
 * <ul>
 *   <li>{@link JdkSerializer#name()} returns the documented constant
 *       {@code "jdk"};</li>
 *   <li>{@link JdkSerializer#instance()} returns the canonical singleton;</li>
 *   <li>serialize → deserialize round-trips preserve value equality for
 *       {@code String}, {@code Integer} and a custom {@code Serializable}
 *       type;</li>
 *   <li>{@code null} inputs are handled gracefully (serialize returns
 *       {@code null}, deserialize returns {@code null});</li>
 *   <li>an empty byte array deserializes to {@code null} (not an
 *       exception);</li>
 *   <li>serializing a non-{@code Serializable} object throws a
 *       {@link RuntimeException} wrapping the underlying
 *       {@link NotSerializableException}.</li>
 * </ul>
 *
 * <p>Test-only fixture types {@link Person} and {@link NotSerializable}
 * are nested at the bottom of this class so the assertions are
 * self-documenting without polluting the production package.
 */
class JdkSerializerTest {

    private final JdkSerializer serializer = JdkSerializer.instance();

    // ── name() and singleton ───────────────────────────────────────────

    @Test
    void nameReturnsJdk() {
        assertThat(serializer.name()).isEqualTo("jdk");
    }

    @Test
    void instanceReturnsSameSingleton() {
        // Statelessness implies a single canonical instance is sufficient;
        // the factory must hand out the same reference on every call.
        assertThat(JdkSerializer.instance()).isSameAs(JdkSerializer.instance());
    }

    // ── null handling ──────────────────────────────────────────────────

    @Test
    void serializeNullReturnsNull() throws Exception {
        // Per the Serializer contract: a null input yields null bytes,
        // NOT an empty byte array, so callers can distinguish "no value"
        // from "empty payload".
        assertThat(serializer.serialize(null)).isNull();
    }

    @Test
    void deserializeNullReturnsNull() throws Exception {
        assertThat(serializer.deserialize(null, new TypeReference<String>() {}))
            .isNull();
    }

    @Test
    void deserializeEmptyBytesReturnsNull() throws Exception {
        // An empty byte array is treated the same as null: no payload to
        // decode. The serializer must not throw on empty input, because
        // a Redis bucket may legitimately return an empty array if a
        // caller stored one via a non-Serializer path.
        assertThat(serializer.deserialize(new byte[0], new TypeReference<String>() {}))
            .isNull();
    }

    // ── round-trip ─────────────────────────────────────────────────────

    @Test
    void roundTripString() throws Exception {
        String original = "hello, cache";
        byte[] bytes = serializer.serialize(original);

        assertThat(bytes).isNotNull();
        assertThat(bytes).isNotEmpty();

        String restored = serializer.deserialize(bytes, new TypeReference<String>() {});
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void roundTripInteger() throws Exception {
        Integer original = 42;
        byte[] bytes = serializer.serialize(original);

        Integer restored = serializer.deserialize(bytes, new TypeReference<Integer>() {});
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void roundTripCustomSerializableObject() throws Exception {
        Person original = new Person("Alice", 30);
        byte[] bytes = serializer.serialize(original);

        Person restored = serializer.deserialize(bytes, new TypeReference<Person>() {});
        assertThat(restored).isEqualTo(original);
        assertThat(restored.name()).isEqualTo("Alice");
        assertThat(restored.age()).isEqualTo(30);
    }

    @Test
    void roundTripNullProducesNullEndToEnd() throws Exception {
        // serialize(null) → null; deserialize(null) → null. The full
        // round-trip of a null value yields null, not a sentinel object.
        byte[] bytes = serializer.serialize(null);
        String restored = serializer.deserialize(bytes, new TypeReference<String>() {});
        assertThat(bytes).isNull();
        assertThat(restored).isNull();
    }

    @Test
    void roundTripPreservesObjectType() throws Exception {
        // Java serialization embeds the concrete class in the stream,
        // so deserialize returns the exact runtime type regardless of
        // the TypeReference parameter. The TypeReference is ignored by
        // JdkSerializer (documented behaviour).
        Person original = new Person("Bob", 25);
        byte[] bytes = serializer.serialize(original);

        // Deserialize with a TypeReference<Person> — the result must be
        // a Person, not a raw Object.
        Object restored = serializer.deserialize(bytes, new TypeReference<Person>() {});
        assertThat(restored).isInstanceOf(Person.class);
    }

    @Test
    void serializeProducesNonZeroLengthForNonEmptyValue() throws Exception {
        // Sanity check: even a small value like an Integer produces a
        // stream header (4 bytes) + object data, so the byte array is
        // never empty for a non-null input.
        byte[] bytes = serializer.serialize(Integer.valueOf(1));
        assertThat(bytes).hasSizeGreaterThan(4);
    }

    // ── non-Serializable rejection ─────────────────────────────────────

    @Test
    void serializeNonSerializableThrowsRuntimeException() {
        // The Serializer SPI declares "throws Exception", but the JDK
        // strategy specifically wraps NotSerializableException in a
        // RuntimeException so that generic callers do not need to catch
        // the JDK-specific checked exception type.
        assertThatThrownBy(() -> serializer.serialize(new NotSerializable()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Serializable")
            .hasCauseInstanceOf(NotSerializableException.class);
    }

    @Test
    void serializeNonSerializableMessageMentionsValueType() {
        // The error message should help the developer identify which
        // value failed to serialize, so it must contain the class name.
        assertThatThrownBy(() -> serializer.serialize(new NotSerializable()))
            .hasMessageContaining(NotSerializable.class.getName());
    }

    // ── test-only fixture types ────────────────────────────────────────

    /**
     * Stand-in for a cacheable entity: simple, immutable, serializable.
     * Used to verify that custom domain types round-trip correctly.
     */
    static final class Person implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String name;
        private final int age;

        Person(String name, int age) {
            this.name = name;
            this.age = age;
        }

        String name() {
            return name;
        }

        int age() {
            return age;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Person other)) return false;
            return age == other.age && Objects.equals(name, other.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, age);
        }
    }

    /**
     * Stand-in for a value that cannot be serialized by the JDK strategy.
     * Deliberately does NOT implement {@link Serializable}.
     */
    static final class NotSerializable {
        // no fields, no Serializable — purely a type-level marker
    }
}
