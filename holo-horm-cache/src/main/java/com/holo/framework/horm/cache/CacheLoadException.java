package com.holo.framework.horm.cache;

/**
 * Thrown when a {@link CacheLoader} (or the {@code loader} supplier passed
 * to the read-through {@link Cache#get} overload) fails to compute a
 * value.
 *
 * <p>The {@link CacheLoader#load} method declares {@code throws Exception},
 * so any checked exception escaping the loader is wrapped in this type by
 * the cache runtime. The original exception is preserved as the cause so
 * that callers can recover the specific failure (e.g. a JDBC
 * {@code SQLException} from a database-backed loader).
 *
 * <p>A loader failure is never cached: the cache does not store a null
 * sentinel or an error marker, so a subsequent {@code get} for the same
 * key will retry the loader. This is deliberate — most loader failures
 * are transient (network blip, deadlock victim) and should be retried
 * rather than served from a cached error. Callers that want to suppress
 * retries during a failure window should implement their own circuit
 * breaker in the loader.
 *
 * <p>Subclassing {@link CacheException} (rather than
 * {@code RuntimeException} directly) lets callers catch all
 * cache-related failures with a single {@code catch (CacheException e)}
 * while still distinguishing load failures via
 * {@code catch (CacheLoadException e)}.
 */
public class CacheLoadException extends CacheException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message; may be {@code null}
     * @param cause   the underlying loader exception; should not be {@code null}
     */
    public CacheLoadException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new exception with the specified cause and a message
     * derived from the cause's class name.
     *
     * @param cause the underlying loader exception; should not be {@code null}
     */
    public CacheLoadException(Throwable cause) {
        super("Cache load failed: " + (cause == null ? "unknown" : cause.toString()), cause);
    }
}
