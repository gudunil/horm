package com.holo.framework.horm.cache;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

/**
 * Redis-backed L2 {@link Cache} implementation using Redisson.
 *
 * <p>{@code RedisCache} stores serialized values in Redis via Redisson's
 * {@link RBucket} abstraction. Each cache entry occupies a single Redis
 * key of the form {@code "<cacheName>:<key>"} and holds a byte-array
 * payload produced by the configured {@link Serializer}. This makes the
 * L2 tier JVM-agnostic: a value written by one instance can be read by
 * any other instance in the cluster.
 *
 * <p><b>M6 scope.</b> This implementation is a functional stub:
 * <ul>
 *   <li>Read-through, single-key and bulk get/put/invalidate are
 *       supported via per-key {@link RBucket} operations.</li>
 *   <li>TTL is honoured through {@link RBucket#set(Object, Duration)}.</li>
 *   <li>Statistics are not collected; {@link #stats()} returns
 *       {@link CacheStats#empty()}. M7+ may wire Micrometer bindings
 *       around Redisson's command latency metrics.</li>
 *   <li><b>Cross-instance invalidation (Pub/Sub) is NOT implemented.</b>
 *       A {@code put} or {@code invalidate} on one JVM does not evict
 *       the corresponding entry from other JVMs' local L1 caches.
 *       Callers requiring cluster-wide consistency must either use only
 *       the L2 tier (no L1) or accept eventual consistency. M7+ will
 *       add a Redisson topic-based invalidation broadcaster — see the
 *       TODO comments on {@link #invalidate(Object)} and
 *       {@link #invalidateAll(Set)}.</li>
 *   <li>Full {@link #invalidateAll()} is not supported: scanning the
 *       {@code "<cacheName>:*"} keyspace is O(N) on Redis and unsafe
 *       against a production cluster. Use {@link #invalidateAll(Set)}
 *       with a known key set instead. M7+ may add a versioned-keyspace
 *       scheme for cheap bulk flush.</li>
 *   <li>The {@link RedissonClient} lifecycle is owned by the caller
 *       (typically a DI container); {@link #close()} is a no-op and
 *       does NOT shut down the client.</li>
 * </ul>
 *
 * <p><b>Thread safety.</b> Redisson clients are safe for concurrent use;
 * this class adds no shared mutable state and is therefore thread-safe.
 *
 * <p><b>Key encoding.</b> Keys are stringified via {@code String.valueOf(key)}
 * and prefixed with the cache name plus a colon. {@code null} keys are
 * rejected. Callers should ensure keys have stable, collision-free
 * {@code toString()} representations.
 */
public final class RedisCache implements Cache {

    private final String name;
    private final RedissonClient client;
    private final Serializer serializer;
    private final CachePolicy defaultPolicy;

    /**
     * Constructs a {@code RedisCache}.
     *
     * <p>This constructor verifies that Redisson is on the classpath via
     * {@code Class.forName("org.redisson.Redisson")}; if the dependency
     * is absent (e.g. the application uses only L1 caching), an
     * {@link IllegalStateException} is thrown with a message indicating
     * which dependency to add.
     *
     * @param name          logical cache name; used as the Redis key prefix
     * @param client        the Redisson client; lifecycle owned by the caller.
     *                      May be {@code null} when the cache is constructed
     *                      only for SPI-level inspection (e.g. unit tests
     *                      that exercise {@link #name()}/{@link #stats()}
     *                      without touching Redis); operations that require
     *                      a live connection will fail with a
     *                      {@link NullPointerException} if the client is null.
     * @param serializer    the value serializer; must not be {@code null}
     * @param defaultPolicy default cache policy; must not be {@code null}
     * @throws IllegalStateException if Redisson is not on the classpath
     */
    public RedisCache(String name,
                      RedissonClient client,
                      Serializer serializer,
                      CachePolicy defaultPolicy) {
        this.name = Objects.requireNonNull(name, "name");
        this.client = client;
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.defaultPolicy = Objects.requireNonNull(defaultPolicy, "defaultPolicy");
        ensureRedissonAvailable();
    }

    /**
     * Static factory that constructs a {@code RedisCache} and translates
     * any classpath-detection failure into a clear {@link IllegalStateException}.
     *
     * <p>This is the recommended construction path for application code:
     * it surfaces the "Redisson missing" failure at a single, documented
     * call site rather than relying on the constructor's unchecked throw.
     *
     * @param name       logical cache name
     * @param client     the Redisson client; may be {@code null} for
     *                   SPI-only usage (see constructor docs)
     * @param serializer the value serializer
     * @param policy     default cache policy
     * @return a non-null {@code RedisCache}
     * @throws IllegalStateException if Redisson is not on the classpath
     */
    public static RedisCache create(String name,
                                    RedissonClient client,
                                    Serializer serializer,
                                    CachePolicy policy) {
        return new RedisCache(name, client, serializer, policy);
    }

    /**
     * Verifies that the Redisson runtime is reachable on the classpath.
     * The check uses {@code Class.forName} on the concrete {@code Redisson}
     * class (rather than the {@link RedissonClient} interface) so that a
     * partial classpath — interface present but implementation jar missing
     * — is still detected.
     */
    private static void ensureRedissonAvailable() {
        try {
            Class.forName("org.redisson.Redisson");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "Redisson is not on the classpath. Add the 'org.redisson:redisson' "
                    + "dependency to enable Redis-backed L2 caching.",
                e);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public CacheLevel level() {
        return CacheLevel.L2;
    }

    @Override
    public <K, V> Optional<V> get(K key, TypeReference<V> type) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        try {
            RBucket<byte[]> bucket = client.getBucket(redisKey(key));
            byte[] bytes = bucket.get();
            // bucket.get() returns null when the key is absent in Redis;
            // serializer.deserialize(null) returns null by contract, so
            // Optional.ofNullable collapses both "absent" and "cached null"
            // into Optional.empty(). M6 does not store null sentinels (see
            // put), so this conflation is harmless.
            return Optional.ofNullable(serializer.deserialize(bytes, type));
        } catch (Exception e) {
            throw new CacheException(
                "Failed to read key " + key + " from Redis cache '" + name + "'", e);
        }
    }

    @Override
    public <K, V> void put(K key, V value, CachePolicy policy) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(policy, "policy");
        // M6: null sentinels are not cached in L2. Storing a null byte[]
        // would be rejected by RBucket.set and would conflate "cached null"
        // with "absent" in the byte[] layer. Negative caching in L2 is a
        // future enhancement tied to Pub/Sub invalidation.
        if (value == null) {
            return;
        }
        try {
            byte[] bytes = serializer.serialize(value);
            if (bytes == null) {
                return;
            }
            RBucket<byte[]> bucket = client.getBucket(redisKey(key));
            Duration ttl = policy.ttl();
            if (ttl.isZero() || ttl.isNegative()) {
                // No expiry: store with an indefinite TTL.
                bucket.set(bytes);
            } else {
                bucket.set(bytes, ttl);
            }
        } catch (Exception e) {
            throw new CacheException(
                "Failed to write key " + key + " to Redis cache '" + name + "'", e);
        }
    }

    @Override
    public <K, V> Optional<V> get(K key,
                                  TypeReference<V> type,
                                  Supplier<V> loader,
                                  CachePolicy policy) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(policy, "policy");
        Optional<V> cached = get(key, type);
        if (cached.isPresent()) {
            return cached;
        }
        V value;
        try {
            value = loader.get();
        } catch (RuntimeException e) {
            // Supplier.get() cannot throw checked exceptions, but it can
            // throw RuntimeExceptions (including CacheLoadException from
            // a nested cache chain). Wrap to satisfy the Cache SPI contract:
            // "A loader that throws is wrapped in CacheLoadException".
            throw new CacheLoadException(
                "Loader failed for key " + key + " in Redis cache '" + name + "'", e);
        }
        // put() is a no-op for null values (M6: no negative caching in L2),
        // so a null loader result is not cached and subsequent reads will
        // re-invoke the loader.
        put(key, value, policy);
        return Optional.ofNullable(value);
    }

    @Override
    public <K, V> Map<K, V> getAll(Set<K> keys, TypeReference<V> type) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(type, "type");
        // M6: simple loop over per-key get(). A Redisson batch or pipeline
        // would reduce round-trips, but the loop keeps the stub transparent
        // and the overhead is negligible for typical HORM cache-lookup
        // cardinalities (single-digit keys per query). M7+ may switch to
        // RBatch + MGET for bulk fetch.
        Map<K, V> result = new HashMap<>();
        for (K key : keys) {
            Optional<V> value = get(key, type);
            value.ifPresent(v -> result.put(key, v));
        }
        return result;
    }

    @Override
    public <K, V> void putAll(Map<K, V> entries, CachePolicy policy) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(policy, "policy");
        // M6: simple loop over per-key put(). M7+ may switch to RBatch.
        for (Map.Entry<K, V> entry : entries.entrySet()) {
            put(entry.getKey(), entry.getValue(), policy);
        }
    }

    @Override
    public <K> void invalidate(K key) {
        Objects.requireNonNull(key, "key");
        // TODO M7: broadcast this invalidation via a Redisson topic so that
        // other JVMs holding the key in their L1 caches also evict it.
        // Without Pub/Sub, a put/invalidate on one instance leaves stale
        // L1 entries on other instances until their TTL expires.
        try {
            RBucket<byte[]> bucket = client.getBucket(redisKey(key));
            bucket.delete();
        } catch (Exception e) {
            throw new CacheException(
                "Failed to invalidate key " + key + " in Redis cache '" + name + "'", e);
        }
    }

    @Override
    public <K> void invalidateAll(Set<K> keys) {
        Objects.requireNonNull(keys, "keys");
        // TODO M7: broadcast bulk invalidation via a Redisson topic.
        for (K key : keys) {
            invalidate(key);
        }
    }

    @Override
    public void invalidateAll() {
        // M6: full flush is not supported. A SCAN-and-DELETE over the
        // "name:*" keyspace is O(N) on Redis and can block the event loop
        // on large keyspaces. Callers should use invalidateAll(Set) with
        // a known key set, or drop the keyspace out-of-band (e.g. via
        // redis-cli KEYS + DEL during a maintenance window).
        // TODO M7: consider a versioned-keyspace scheme (e.g. "name:v2:*")
        // that allows cheap bulk flush by bumping the version prefix.
        throw new UnsupportedOperationException(
            "Full invalidateAll() is not supported in M6; use invalidateAll(Set) "
                + "with a known key set instead");
    }

    @Override
    public CacheStats stats() {
        // Redisson exposes no per-bucket hit/miss counters. M6 returns the
        // canonical empty snapshot; M7+ may wire Micrometer bindings around
        // Redisson's command latency metrics to derive hit rate from
        // get-vs-set command ratios.
        return CacheStats.empty();
    }

    @Override
    public void close() {
        // No-op: the RedissonClient lifecycle is owned by the caller
        // (typically a Spring bean / DI container). Closing it here would
        // break other caches sharing the same client. The Cache SPI
        // contract requires idempotency, which a no-op satisfies trivially.
    }

    private String redisKey(Object key) {
        return name + ":" + key;
    }
}
