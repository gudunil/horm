package com.holo.framework.horm.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;

/**
 * L1 in-process {@link Cache} backed by <a href="https://github.com/ben-manes/caffeine">Caffeine</a>.
 *
 * <p>This is the default L1 implementation for the HORM cache chain: it
 * holds direct object references (zero serialization), enforces size and
 * TTL eviction natively via Caffeine's windowed TinyLFU policy, and
 * surfaces hit/miss/eviction counters through {@link #stats()}.
 *
 * <p><b>Note:</b> throughout this class, {@code Cache} refers to the HORM
 * SPI {@link com.holo.framework.horm.cache.Cache}; the underlying
 * Caffeine type is always referenced by its fully-qualified name to
 * avoid the obvious name clash.
 *
 * <h2>Availability</h2>
 *
 * <p>Caffeine is declared {@code <optional>true</optional>} in the cache
 * module's POM, so downstream consumers that want L1 support must declare
 * the dependency explicitly. The constructor performs a
 * {@code Class.forName} probe and throws {@link IllegalStateException}
 * when Caffeine is absent; callers that prefer a no-op fallback should
 * catch the exception and substitute a {@code NoOpCache}:
 *
 * <pre>{@code
 * Cache l1;
 * try {
 *     l1 = CaffeineCache.create("users-l1", policy);
 * } catch (IllegalStateException e) {
 *     l1 = new NoOpCache("users-l1", CacheLevel.L1);
 * }
 * }</pre>
 *
 * <h2>Configuration model</h2>
 *
 * <p>Caffeine's builder is immutable once built, so a single
 * {@link CachePolicy} supplied at construction time configures the
 * underlying Caffeine instance for the cache's lifetime:
 * <ul>
 *   <li>{@link CachePolicy#ttl()} → Caffeine {@code expireAfterWrite};</li>
 *   <li>{@link CachePolicy#maxEntries()} → Caffeine {@code maximumSize}
 *       (a value of {@code -1} leaves the cache unbounded);</li>
 *   <li>Statistics are always recorded ({@code recordStats()}).</li>
 * </ul>
 *
 * <p>The {@code policy} argument passed to {@link #put}/{@link #get} at
 * runtime is consulted only for the {@link CachePolicy#nullable()}
 * decision (whether a {@code null} value may be stored). Per-entry TTL
 * is not supported by this implementation: the underlying Caffeine
 * instance uses a single TTL configured at construction time. A future
 * revision may surface per-entry TTL via Caffeine's variable-expiration
 * API.
 *
 * <h2>Null value handling</h2>
 *
 * <p>Caffeine itself rejects {@code null} values, so this cache does not
 * store null sentinels. {@code put(key, null, policy)} with
 * {@code nullable == true} is a tolerated no-op (returns without
 * writing); with {@code nullable == false} it throws
 * {@link IllegalArgumentException} per the {@link Cache} contract.
 * Negative caching of absent keys is the responsibility of the
 * surrounding {@link CacheChain} (which can wrap values in a null
 * marker before they reach this cache), not of {@code CaffeineCache}
 * itself.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Safe for concurrent use by multiple threads. Caffeine's internal
 * data structures are lock-free; the {@link CopyOnWriteArrayList} used
 * for listeners is also thread-safe. {@link #close()} is idempotent.
 *
 * <h2>Resource lifecycle</h2>
 *
 * <p>{@link #close()} triggers {@code cleanUp()} followed by
 * {@code invalidateAll()} on the underlying Caffeine instance so that
 * pending maintenance tasks (e.g. notifying the removal listener of
 * evicted entries) run before the cache is cleared. Caffeine does not
 * hold native resources that require explicit release, so {@code close()}
 * is best-effort cleanup rather than a hard resource-release. Behaviour
 * after {@code close()} is unspecified.
 */
public final class CaffeineCache implements Cache {

    private final String name;
    private final CachePolicy defaultPolicy;
    private final com.github.benmanes.caffeine.cache.Cache<Object, Object> caffeine;
    private final List<CacheEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed = false;

    /**
     * Constructs a new Caffeine-backed L1 cache.
     *
     * <p>The Caffeine instance is configured from {@code defaultPolicy}
     * at construction time and is immutable thereafter. See the class
     * Javadoc for the field-to-Caffeine mapping.
     *
     * @param name          logical cache name; must not be {@code null} or blank
     * @param defaultPolicy policy used to configure the underlying Caffeine
     *                      builder (TTL, max entries); must not be {@code null}
     * @throws IllegalStateException     if Caffeine is not on the classpath
     * @throws IllegalArgumentException if {@code name} is blank
     * @throws NullPointerException     if {@code defaultPolicy} is {@code null}
     */
    public CaffeineCache(String name, CachePolicy defaultPolicy) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        this.name = name;
        this.defaultPolicy = Objects.requireNonNull(defaultPolicy, "defaultPolicy");
        // Probe Caffeine availability eagerly so a missing dependency is
        // surfaced at construction rather than on first use.
        try {
            Class.forName("com.github.benmanes.caffeine.cache.Cache");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "Caffeine is not on the classpath; add the "
                    + "com.github.ben-manes.caffeine:caffeine dependency "
                    + "or fall back to NoOpCache", e);
        }
        this.caffeine = buildCaffeine(this.name, this.defaultPolicy, this.listeners);
    }

    /**
     * Builds the underlying Caffeine instance from {@code policy}.
     *
     * <p>Extracted as a helper so the constructor body stays readable;
     * not static because it captures the {@code listeners} list for the
     * removal listener callback.
     */
    private static com.github.benmanes.caffeine.cache.Cache<Object, Object> buildCaffeine(String cacheName,
                                                       CachePolicy policy,
                                                       List<CacheEventListener> listeners) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
            .recordStats()
            .removalListener((RemovalListener<Object, Object>) (key, value, cause) ->
                dispatchRemoval(cacheName, listeners, key, value, cause));

        Duration ttl = policy.ttl();
        if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
            builder.expireAfterWrite(ttl);
        }

        int maxEntries = policy.maxEntries();
        if (maxEntries > 0) {
            builder.maximumSize(maxEntries);
        }
        // maxEntries == -1 → unbounded; Caffeine's default is unbounded
        // so we leave the builder untouched in that case.

        return builder.build();
    }

    /**
     * Translates a Caffeine {@link RemovalCause} into a {@link CacheEvent}
     * and dispatches it to every registered listener.
     *
     * <p>Only size/eviction and expiry causes generate events here; the
     * {@code EXPLICIT} cause (raised by {@link #invalidate}) and the
     * {@code REPLACED} cause (raised by re-{@code put}ting a key) are
     * ignored because the corresponding user-facing operations publish
     * their own {@link CacheEventType#INVALIDATE} events directly, and
     * duplicate notifications would only confuse listeners.
     */
    private static void dispatchRemoval(String cacheName,
                                        List<CacheEventListener> listeners,
                                        Object key,
                                        Object value,
                                        RemovalCause cause) {
        if (listeners.isEmpty()) {
            return;
        }
        final CacheEventType eventType;
        switch (cause) {
            case EXPIRED -> eventType = CacheEventType.EXPIRE;
            case SIZE -> eventType = CacheEventType.EVICT;
            case COLLECTED -> eventType = CacheEventType.EVICT;
            default -> {
                // EXPLICIT and REPLACED are surfaced by the calling
                // operation; suppress here to avoid duplicate events.
                return;
            }
        }
        CacheEvent event = CacheEvent.of(eventType, cacheName, CacheLevel.L1, key, value);
        for (CacheEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException ex) {
                // Listener isolation: a throwing listener must not abort
                // the cache operation. Swallow and continue; production
                // deployments that want visibility should wire SLF4J.
            }
        }
    }

    /**
     * Convenience factory — equivalent to {@code new CaffeineCache(name, policy)}.
     *
     * <p>Provided as the documented entry point so callers that want a
     * graceful fallback can write a single {@code try/catch(IllegalStateException)}
     * around the creation site without referencing the constructor
     * directly. The rethrown {@link IllegalStateException} is the signal
     * to substitute a {@code NoOpCache}.
     *
     * @param name   logical cache name
     * @param policy default policy for the underlying Caffeine builder
     * @return a non-null, ready-to-use {@code CaffeineCache}
     * @throws IllegalStateException if Caffeine is unavailable on the classpath
     */
    public static CaffeineCache create(String name, CachePolicy policy) {
        return new CaffeineCache(name, policy);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public CacheLevel level() {
        return CacheLevel.L1;
    }

    /**
     * Returns the {@link CachePolicy} used to configure this cache at
     * construction time. The returned policy is the immutable snapshot
     * captured by the constructor; runtime {@code policy} arguments to
     * {@link #put}/{@link #get} do not mutate it.
     *
     * @return a non-null {@link CachePolicy}
     */
    public CachePolicy defaultPolicy() {
        return defaultPolicy;
    }

    @Override
    public <K, V> Optional<V> get(K key, TypeReference<V> type) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        // L1 holds direct references; type is irrelevant for in-process reads
        // but is required by the SPI contract so that providers that need to
        // deserialise can recover the value type.
        @SuppressWarnings("unchecked")
        V value = (V) caffeine.getIfPresent(key);
        return Optional.ofNullable(value);
    }

    @Override
    public <K, V> void put(K key, V value, CachePolicy policy) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(policy, "policy");
        if (value == null) {
            if (!policy.nullable()) {
                throw new IllegalArgumentException(
                    "Cannot cache null value under key " + key
                        + " when policy.nullable() is false");
            }
            // Caffeine rejects null values; rather than introduce a
            // NullMarker here (and risk conflicting with the chain-level
            // NullMarker), we treat put(null) as a tolerated no-op.
            // Negative caching of absent keys is the chain's job.
            return;
        }
        caffeine.put(key, value);
    }

    @Override
    public <K, V> Optional<V> get(K key, TypeReference<V> type, Supplier<V> loader, CachePolicy policy) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(policy, "policy");

        @SuppressWarnings("unchecked")
        V cached = (V) caffeine.getIfPresent(key);
        if (cached != null) {
            return Optional.of(cached);
        }

        V loaded;
        try {
            loaded = loader.get();
        } catch (RuntimeException ex) {
            throw new CacheLoadException(ex);
        } catch (Exception ex) {
            // Supplier#get doesn't declare checked exceptions, but a
            // loader that wraps a CacheLoader might rethrow via
            // sneaky throws; defend against that here.
            throw new CacheLoadException(ex);
        }

        if (loaded == null) {
            // Negative caching is handled at the chain layer; CaffeineCache
            // itself does not store null sentinels.
            return Optional.empty();
        }
        caffeine.put(key, loaded);
        return Optional.of(loaded);
    }

    @Override
    public <K, V> Map<K, V> getAll(Set<K> keys, TypeReference<V> type) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(type, "type");
        if (keys.isEmpty()) {
            return new HashMap<>();
        }
        Map<Object, Object> present = caffeine.getAllPresent(keys);
        if (present.isEmpty()) {
            return new HashMap<>();
        }
        // Build a typed map keyed by the original K values.
        Map<K, V> result = new HashMap<>(present.size());
        for (Map.Entry<Object, Object> entry : present.entrySet()) {
            @SuppressWarnings("unchecked")
            K k = (K) entry.getKey();
            @SuppressWarnings("unchecked")
            V v = (V) entry.getValue();
            result.put(k, v);
        }
        return result;
    }

    @Override
    public <K, V> void putAll(Map<K, V> entries, CachePolicy policy) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(policy, "policy");
        if (entries.isEmpty()) {
            return;
        }
        // Filter out null values: Caffeine rejects them, and the chain
        // layer owns negative caching. If nullable == false we still
        // honour the contract by throwing on the first null value
        // encountered (fail-fast) before writing anything.
        Map<Object, Object> filtered = new HashMap<>(entries.size());
        for (Map.Entry<K, V> entry : entries.entrySet()) {
            K key = entry.getKey();
            if (key == null) {
                throw new NullPointerException("entries contains a null key");
            }
            V value = entry.getValue();
            if (value == null) {
                if (!policy.nullable()) {
                    throw new IllegalArgumentException(
                        "Cannot cache null value under key " + key
                            + " when policy.nullable() is false");
                }
                continue;
            }
            filtered.put(key, value);
        }
        if (!filtered.isEmpty()) {
            caffeine.putAll(filtered);
        }
    }

    @Override
    public <K> void invalidate(K key) {
        Objects.requireNonNull(key, "key");
        caffeine.invalidate(key);
        publishEvent(CacheEventType.INVALIDATE, key, null);
    }

    @Override
    public <K> void invalidateAll(Set<K> keys) {
        Objects.requireNonNull(keys, "keys");
        if (keys.isEmpty()) {
            return;
        }
        // Defensive copy: Caffeine iterates the supplied set, and we
        // also use it for the INVALIDATE event payload below — avoid
        // sharing a mutable view with the caller.
        Collection<K> snapshot = new ArrayList<>(keys);
        caffeine.invalidateAll(snapshot);
        // Per the CacheChain contract, the bulk-invalidate event may
        // carry a null key since we cannot reasonably fire one event
        // per key in a bulk round-trip.
        publishEvent(CacheEventType.INVALIDATE, null, null);
    }

    @Override
    public void invalidateAll() {
        caffeine.invalidateAll();
        publishEvent(CacheEventType.INVALIDATE, null, null);
    }

    @Override
    public CacheStats stats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats s = caffeine.stats();
        long hits = s.hitCount();
        long misses = s.missCount();
        long evictions = s.evictionCount();
        // Caffeine does not separately expose expiration count; the
        // eviction counter aggregates both SIZE and EXPIRED removals.
        long expirations = 0L;
        long loads = s.loadSuccessCount() + s.loadFailureCount();
        long loadFailures = s.loadFailureCount();
        Duration averageLoadTime = Duration.ofNanos((long) s.averageLoadPenalty());
        long estimatedSize = caffeine.estimatedSize();
        return new CacheStats(hits, misses, evictions, expirations, loads,
            loadFailures, averageLoadTime, estimatedSize);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Run pending maintenance (listener notification for evicted
        // entries, etc.) before clearing the cache so that listeners
        // observe the full lifecycle.
        caffeine.cleanUp();
        caffeine.invalidateAll();
        caffeine.cleanUp();
    }

    /**
     * Registers a {@link CacheEventListener} to receive
     * {@link CacheEvent}s from this cache.
     *
     * <p>Listeners are notified on the thread that triggers the event
     * (the caller of {@code put}/{@code invalidate} or Caffeine's
     * maintenance cycle for {@code EXPIRE}/{@code EVICT} events). A
     * throwing listener is isolated: its exception is swallowed and the
     * next listener is still invoked.
     *
     * <p>This method is intentionally not on the {@link Cache} SPI; it
     * is a {@code CaffeineCache}-specific extension. Chains that want
     * cross-tier listener registration should do so at the
     * {@link CacheChain} layer.
     *
     * @param listener the listener to register; must not be {@code null}
     */
    public void addEventListener(CacheEventListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
    }

    /**
     * Removes a previously-registered listener. No-op if the listener
     * was never registered.
     *
     * @param listener the listener to remove; must not be {@code null}
     */
    public void removeEventListener(CacheEventListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.remove(listener);
    }

    /**
     * Helper that publishes an event to every registered listener with
     * failure isolation.
     */
    private void publishEvent(CacheEventType type, Object key, Object value) {
        if (listeners.isEmpty()) {
            return;
        }
        CacheEvent event = CacheEvent.of(type, name, CacheLevel.L1, key, value);
        for (CacheEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException ex) {
                // Swallow — see addEventListener Javadoc.
            }
        }
    }

    /**
     * Package-private test hook that synchronously drives Caffeine's
     * maintenance cycle. Production callers should never need this:
     * Caffeine performs cleanup opportunistically on read/write paths
     * and on its scheduler. Tests, however, need deterministic
     * post-state assertions (estimated size, eviction listener
     * invocation, expiry processing) and cannot rely on the
     * best-effort timing. This hook lets tests in the same package
     * force a {@code cleanUp()} without widening the public API.
     */
    void cleanUpForTest() {
        caffeine.cleanUp();
    }
}
