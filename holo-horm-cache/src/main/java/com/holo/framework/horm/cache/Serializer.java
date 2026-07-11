package com.holo.framework.horm.cache;

/**
 * Strategy for serializing cache values to bytes for remote tiers
 * (L2 Redis, etc.).
 *
 * <p>Remote cache providers cannot hold direct object references across
 * JVM boundaries, so values must be converted to {@code byte[]} on write
 * and reconstructed on read. This SPI abstracts the encoding format so
 * that a {@link RedisCache} (or any future remote {@link Cache}) can be
 * composed with different serializers — JDK serialization, JSON, Kryo,
 * Protobuf — without the cache implementation knowing the wire format.
 *
 * <p><b>Contract.</b>
 * <ul>
 *   <li>{@link #name()} returns a short, stable identifier (e.g.
 *       {@code "jdk"}, {@code "json"}) used in logging, metrics and
 *       configuration; implementations should ensure uniqueness across
 *       registered serializers.</li>
 *   <li>{@link #serialize(Object)} accepts any object (or {@code null})
 *       and returns its byte-encoded form. A {@code null} input SHOULD
 *       return {@code null} (not an empty byte array) so that the caller
 *       can distinguish "no value to store" from "empty payload".</li>
 *   <li>{@link #deserialize(byte[], TypeReference)} reverses the
 *       encoding. A {@code null} or empty byte array SHOULD return
 *       {@code null}. The {@link TypeReference} parameter carries the
 *       expected value type; serializers that preserve type information
 *       natively (e.g. JDK serialization) may ignore it, while
 *       format-agnostic serializers (e.g. JSON) consult it to drive
 *       deserialization.</li>
 * </ul>
 *
 * <p><b>Thread safety.</b> Implementations MUST be safe for concurrent
 * use by multiple threads. Stateless serializers (the common case) satisfy
 * this trivially; stateful serializers must guard their internal state.
 *
 * <p><b>Error handling.</b> Both methods declare {@code throws Exception}
 * to accommodate serializers whose encoding step throws checked
 * exceptions (e.g. JSON serializers throwing {@code IOException}).
 * Cache implementations wrap these in {@link CacheException} so that
 * callers are not exposed to checked exceptions.
 */
public interface Serializer {

    /**
     * Returns the short, stable name of this serializer (e.g. {@code "jdk"}).
     *
     * @return a non-null, non-empty identifier
     */
    String name();

    /**
     * Serializes the given value to a byte array.
     *
     * @param value the value to serialize; may be {@code null}
     * @return the byte-encoded form, or {@code null} if {@code value}
     *         is {@code null}
     * @throws Exception if serialization fails (e.g. the value is not
     *         serializable by this strategy)
     */
    byte[] serialize(Object value) throws Exception;

    /**
     * Deserializes the given byte array back into an object of the
     * expected type.
     *
     * @param bytes the byte array produced by {@link #serialize}; may be
     *              {@code null} or empty
     * @param type  the expected value type; serializers that preserve
     *              type information natively may ignore this parameter
     * @param <V>   the value type
     * @return the deserialized object, or {@code null} if {@code bytes}
     *         is {@code null} or empty
     * @throws Exception if deserialization fails
     */
    <V> V deserialize(byte[] bytes, TypeReference<V> type) throws Exception;
}
