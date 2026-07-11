package com.holo.framework.horm.cache;

/**
 * Strategy for computing the value of an absent cache entry on demand.
 *
 * <p>Implementations are invoked by a {@link Cache} on a miss, typically
 * from the read-through overload
 * {@code get(key, type, loader, policy)}. The loader is responsible for
 * fetching the authoritative value (e.g. issuing a database query via
 * {@code Model.find}); the cache then caches the result according to
 * the active {@link CachePolicy}.
 *
 * <p>The {@code throws Exception} clause is intentional: HORM cache
 * implementations must wrap checked exceptions in {@link CacheLoadException}
 * (a {@link RuntimeException}) so that call sites stay clean, while still
 * surfacing the original cause via {@link Throwable#getCause()}.
 *
 * <p>Loaders must be idempotent with respect to key lookups: a cache may
 * retry a failed loader under contention, and distributed caches may
 * coalesce concurrent loads for the same key onto a single loader call.
 *
 * @param <K> key type
 * @param <V> value type
 */
@FunctionalInterface
public interface CacheLoader<K, V> {

    /**
     * Computes the value for the given key.
     *
     * @param key the cache key whose value is missing
     * @return the value to cache (may be {@code null} if the active
     *         {@link CachePolicy} has {@code nullable == true})
     * @throws Exception if the load fails; the cache implementation wraps
     *         this in a {@link CacheLoadException}
     */
    V load(K key) throws Exception;
}
