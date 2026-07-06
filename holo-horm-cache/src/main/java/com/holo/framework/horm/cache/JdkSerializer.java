package com.holo.framework.horm.cache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * {@link Serializer} backed by Java's built-in object serialization
 * ({@link ObjectOutputStream} / {@link ObjectInputStream}).
 *
 * <p>This is the default serializer for remote cache tiers when no
 * external serialization library (Jackson, Kryo, etc.) is configured.
 * It requires cached values to implement {@link java.io.Serializable},
 * which is the case for most HORM entity types (records, DTOs).
 *
 * <p><b>Characteristics.</b>
 * <ul>
 *   <li><b>Format:</b> Java serialization stream (binary, not
 *       human-readable).</li>
 *   <li><b>Type preservation:</b> full — the class name and field
 *       types are embedded in the stream, so {@link #deserialize}
 *       ignores the {@link TypeReference} parameter and relies on the
 *       stored type information.</li>
 *   <li><b>Performance:</b> moderate. Java serialization is slower than
 *       Kryo or Protobuf and produces larger payloads, but it has no
 *       external dependencies and works for any {@code Serializable}
 *       type out of the box.</li>
 *   <li><b>Security:</b> Java deserialization is vulnerable to
 *       deserialization-of-untrusted-data attacks. The Redis L2 cache
 *       is intended for trusted, intra-cluster traffic only; do not
 *       expose it to untrusted clients. M7+ may introduce an
 *       {@code ObjectInputFilter} for defense-in-depth.</li>
 * </ul>
 *
 * <p><b>Statelessness.</b> {@code JdkSerializer} holds no mutable state
 * and is therefore safe for concurrent use. A single canonical instance
 * is exposed via {@link #instance()}; there is no reason to construct
 * additional instances.
 */
public final class JdkSerializer implements Serializer {

    /** Canonical singleton instance; the class is stateless. */
    private static final JdkSerializer INSTANCE = new JdkSerializer();

    /** Name returned by {@link #name()}; stable for the lifetime of the class. */
    private static final String NAME = "jdk";

    private JdkSerializer() {
        // no state to initialise
    }

    /**
     * Returns the canonical singleton {@code JdkSerializer}.
     *
     * @return the singleton instance; never {@code null}
     */
    public static JdkSerializer instance() {
        return INSTANCE;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public byte[] serialize(Object value) throws Exception {
        if (value == null) {
            return null;
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(value);
            oos.flush();
            return baos.toByteArray();
        } catch (NotSerializableException e) {
            // Wrap the checked NotSerializableException in a RuntimeException
            // so callers using the Serializer SPI generically can surface a
            // clear "value not serializable" failure without catching the
            // JDK-specific exception type. The original exception is preserved
            // as the cause for diagnostics.
            throw new RuntimeException(
                "Cannot serialize object of type " + value.getClass().getName()
                    + ": it does not implement java.io.Serializable", e);
        }
    }

    @Override
    public <V> V deserialize(byte[] bytes, TypeReference<V> type) throws Exception {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            // Java serialization embeds the concrete class in the stream,
            // so the TypeReference parameter is not consulted. The unchecked
            // cast is safe because the caller asked for type V and the stream
            // contains the originally serialized object; a mismatch surfaces
            // as a ClassCastException at the call site, which is the expected
            // failure mode for a type-unsafe deserializer.
            @SuppressWarnings("unchecked")
            V value = (V) ois.readObject();
            return value;
        }
    }
}
