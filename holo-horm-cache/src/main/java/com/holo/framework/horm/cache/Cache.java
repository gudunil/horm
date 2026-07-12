package com.holo.framework.horm.cache;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * SPI contract for a single cache tier (one level of a {@link CacheChain}).
 *
 * <p>A {@code Cache} is a named, level-tagged key/value store with
 * optional read-through loading, bulk operations and statistics. The
 * interface is deliberately provider-agnostic: a Caffeine-backed L1, a
 * Redisson-backed L2 and a JDBC-backed L3 all implement the same
 * contract, so a {@link CacheChain} can compose them uniformly.
 *
 * <p><b>Generics.</b> Key and value types are left as parameters of each
 * method rather than of the interface itself, because a single cache
 * instance frequently serves multiple entity types under string keys.
 * Callers pass a {@link TypeReference} on read operations so that
 * providers that need to deserialise (e.g. Redisson decoding JSON) can
 * recover the concrete value type.
 *
 * <p><b>Nullability.</b> {@link #get(Object, TypeReference)} returns
 * {@link Optional} to distinguish "absent" from "cached null sentinel".
 * Whether a null sentinel is stored at all is governed by
 * {@link CachePolicy#nullable()}.
 *
 * <p><b>Thread safety.</b> Implementations MUST be safe for concurrent
 * use by multiple threads. Per-key load coalescing is encouraged but not
 * required; the contract only mandates that a concurrent {@code put}
 * for an absent key is observable as either the loader's value or the
 * racing value, never a corrupt hybrid.
 *
 * <p><b>Resource lifecycle.</b> {@code Cache} extends
 * {@link AutoCloseable} so that callers can release native resources
 * (Redisson clients, off-heap buffers, scheduled executors for TTL
 * maintenance) via try-with-resources. The {@link #close()} method must
 * be idempotent.
 */
public interface Cache extends AutoCloseable {

    /**
     * Logical name of this cache (e.g. {@code "users-l1"}). Used in
     * {@link CacheEvent}s, log output and metrics tags. Stable for the
     * lifetime of the cache.
     *
     * @return a non-null, non-empty cache name
     */
    String name();

    /**
     * Position of this cache in the multi-level topology.
     *
     * @return the {@link CacheLevel}; never {@code null}
     */
    CacheLevel level();

    /**
     * Fetches the value associated with {@code key}, if present and live.
     *
     * <p>Implementations that store serialized payloads (e.g. Redis)
     * consult {@code type} to drive deserialisation. In-process caches
     * may ignore {@code type} and return the stored reference as-is.
     *
     * @param key  the cache key; must not be {@code null}
     * @param type the expected value type; must not be {@code null}
     * @param <K>  key type
     * @param <V>  value type
     * @return {@link Optional#empty()} if the key is absent or expired;
     *         {@link Optional#ofNullable(Object)} otherwise (so a cached
     *         null sentinel yields {@code Optional.empty()} only when
     *         negative caching is disabled; consult the implementation's
     *         documented sentinel behaviour)
     */
    <K, V> Optional<V> get(K key, TypeReference<V> type);

    /**
     * Fetches the value associated with {@code key}, if present and live.
     *
     * <p>Convenience overload for simple (non-generic) value types that
     * avoids the need for a {@link TypeReference} anonymous subclass.
     * Equivalent to {@code get(key, TypeReference.of(type))}.
     *
     * @param key  the cache key; must not be {@code null}
     * @param type the expected value class; must not be {@code null}
     * @param <K>  key type
     * @param <V>  value type
     * @return the cached value wrapped in {@link Optional}, or
     *         {@link Optional#empty()} if absent
     */
    default <K, V> Optional<V> get(K key, Class<V> type) {
        return get(key, TypeReference.of(type));
    }

    /**
     * Stores {@code value} under {@code key} using the rules of {@code policy}.
     *
     * <p>Whether the value is also propagated to a backing store depends
     * on {@link CachePolicy#writeStrategy()}; the cache may invoke a
     * configured {@link CacheWriter} as a side effect of this call.
     *
     * @param key    the cache key; must not be {@code null}
     * @param value  the value to store; may be {@code null} only when
     *               {@link CachePolicy#nullable()} is {@code true}
     * @param policy the policy governing TTL, eviction, write strategy;
     *               must not be {@code null}
     * @param <K>    key type
     * @param <V>    value type
     */
    <K, V> void put(K key, V value, CachePolicy policy);

    /**
     * Read-through variant of {@link #get}: if the key is absent or
     * expired, the {@code loader} is invoked to compute the value, which
     * is then cached under {@code policy} before being returned.
     *
     * <p>Implementations should coalesce concurrent loads for the same
     * key when feasible (e.g. Caffeine's {@code LoadingCache} does this
     * natively). A loader that throws is wrapped in
     * {@link CacheLoadException} (preserving the cause) and not cached.
     *
     * @param key    the cache key; must not be {@code null}
     * @param type   the expected value type; must not be {@code null}
     * @param loader the value supplier invoked on miss; must not be {@code null}
     * @param policy the policy governing the cached entry; must not be {@code null}
     * @param <K>    key type
     * @param <V>    value type
     * @return the cached or freshly loaded value, wrapped in
     *         {@link Optional#empty()} if both the cache and the loader
     *         yielded {@code null} (only possible when
     *         {@link CachePolicy#nullable()} is {@code true})
     */
    <K, V> Optional<V> get(K key, TypeReference<V> type, Supplier<V> loader, CachePolicy policy);

    /**
     * Bulk fetch of the entries for {@code keys}.
     *
     * <p>The returned map contains an entry for every key that was
     * present and live; absent keys are omitted (callers can compute
     * the missing set via {@code Sets.difference(keys, result.keySet())}).
     * Implementations SHOULD favour a single round-trip when the backing
     * store supports it (e.g. Redis {@code MGET}).
     *
     * @param keys the keys to fetch; must not be {@code null} or empty
     * @param type the expected value type; must not be {@code null}
     * @param <K>  key type
     * @param <V>  value type
     * @return a non-null, possibly-empty map of found entries
     */
    <K, V> Map<K, V> getAll(Set<K> keys, TypeReference<V> type);

    /**
     * Bulk store of {@code entries} under {@code policy}.
     *
     * <p>Implementations SHOULD apply the entries atomically when the
     * backing store supports it (e.g. Redis {@code MSET}); otherwise
     * partial application is permitted and the cache may surface the
     * partial state via a {@link CacheEvent} of type
     * {@link CacheEventType#ERROR}.
     *
     * @param entries the entries to store; must not be {@code null}
     * @param policy  the policy governing the stored entries; must not be {@code null}
     * @param <K>     key type
     * @param <V>     value type
     */
    <K, V> void putAll(Map<K, V> entries, CachePolicy policy);

    /**
     * Removes the entry for {@code key}, if present. Emits an
     * {@link CacheEventType#INVALIDATE} event.
     *
     * @param key the cache key; must not be {@code null}
     * @param <K> key type
     */
    <K> void invalidate(K key);

    /**
     * Removes the entries for {@code keys}. Implementations SHOULD favour
     * a bulk round-trip when the backing store supports it. The
     * {@link CacheEventType#INVALIDATE} event for bulk invalidation may
     * carry {@code null} as the key (since the listener cannot
     * reasonably be invoked once per key in a bulk round-trip).
     *
     * @param keys the keys to invalidate; must not be {@code null}
     * @param <K>  key type
     */
    <K> void invalidateAll(Set<K> keys);

    /**
     * Removes every entry in this cache. Expensive on most providers;
     * callers should prefer {@link #invalidateAll(Set)} when the target
     * key set is known.
     */
    void invalidateAll();

    /**
     * Returns a snapshot of operational counters. Implementations are
     * free to return either lifetime counters or a windowed view; the
     * choice is documented by the implementation. The returned object
     * is immutable and safe to retain.
     *
     * @return a non-null {@link CacheStats} snapshot
     */
    CacheStats stats();

    /**
     * Releases any resources held by this cache (connections, threads,
     * off-heap buffers). Idempotent: calling {@code close()} on an
     * already-closed cache is a no-op.
     *
     * <p>After {@code close()} returns, the cache's behaviour on
     * subsequent method calls is unspecified; callers should discard
     * the reference.
     */
    @Override
    void close();
}
