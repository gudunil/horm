package com.holo.framework.horm.cache;

/**
 * Strategy for propagating cache writes to the authoritative data source.
 *
 * <p>Invoked by a {@link Cache} when the active {@link CachePolicy}
 * requests {@link WriteStrategy#THROUGH} or {@link WriteStrategy#BEHIND}
 * semantics: the writer is the bridge that turns a cache {@code put}
 * into a database update (or whatever the system of record happens to be).
 *
 * <p>For {@link WriteStrategy#THROUGH}, the writer is called synchronously
 * inside {@link Cache#put}; failure aborts the put and the exception
 * propagates to the caller. For {@link WriteStrategy#BEHIND}, the writer
 * is invoked asynchronously by the cache's write queue; failures are
 * routed to the queue's error handler rather than to the original caller.
 *
 * <p>The {@code throws Exception} clause mirrors {@link CacheLoader}:
 * implementations are encouraged to throw checked exceptions naturally,
 * and the cache runtime is responsible for wrapping them in
 * {@link CacheException} where appropriate.
 *
 * <p>Writers must be idempotent: a write-behind queue may redeliver a
 * write after a partial failure, and a write-through cache may retry on
 * transient data-source errors. Subclasses that perform non-idempotent
 * mutations (e.g. balance decrement) must guard with version checks.
 *
 * @param <K> key type
 * @param <V> value type
 */
@FunctionalInterface
public interface CacheWriter<K, V> {

    /**
     * Persists the given key/value pair to the backing store.
     *
     * @param key   the cache key being written
     * @param value the value being written (may be {@code null} on
     *              explicit invalidation by some strategies)
     * @throws Exception if the write fails; the cache implementation
     *         decides whether to wrap, propagate or queue for retry
     */
    void write(K key, V value) throws Exception;
}
