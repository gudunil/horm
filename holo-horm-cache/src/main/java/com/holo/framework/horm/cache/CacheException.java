package com.holo.framework.horm.cache;

/**
 * Unchecked exception thrown by the HORM cache runtime when an operation
 * fails for a reason other than loader failure.
 *
 * <p>This is the base type for all cache-related exceptions in the
 * HORM cache module; subclasses ({@link CacheLoadException}) specialise
 * the cause. Callers that want a single catch block can catch
 * {@code CacheException}; callers that want fine-grained handling can
 * catch the specific subclass first.
 *
 * <p>Wrapping checked exceptions in a single {@code RuntimeException}
 * hierarchy keeps cache call sites clean (no {@code try/catch} for
 * {@code IOException} on a Redis round-trip) while still giving callers
 * a precise type to catch when they need to react to cache failures.
 *
 * <p>Typical causes wrapped by this exception:
 * <ul>
 *   <li>serialization failures when a {@link Cache} implementation
 *       round-trips values through JSON / Kryo / etc.;</li>
 *   <li>connection errors to a distributed cache backend (Redis cluster
 *       unreachable, etc.);</li>
 *   <li>configuration errors (invalid {@link CachePolicy}, missing
 *       required SPI implementation);</li>
 *   <li>write-behind queue overflow or persistence failure.</li>
 * </ul>
 */
public class CacheException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message; may be {@code null}
     */
    public CacheException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message; may be {@code null}
     * @param cause   the underlying cause; may be {@code null}
     */
    public CacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
