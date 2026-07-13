package com.holo.framework.horm.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Default {@link CacheChain} implementation: a mutable, in-process chain
 * that composes one or more {@link Cache} tiers in lookup order and
 * provides read-through, write-through and invalidation propagation
 * across the whole stack.
 *
 * <p><b>Topology.</b> The chain holds an ordered {@code List<Cache>}
 * where index {@code 0} is the closest tier (typically L1) and the last
 * index is the farthest (typically L3). {@code get} walks the list in
 * order; the first tier that returns a value (or a {@link NullMarker})
 * wins, and the chain back-fills every tier above the hit on the way
 * back. This is what makes the chain behave as a coherent multi-level
 * cache rather than a fallback ladder.
 *
 * <p><b>Negative caching.</b> When {@link CachePolicy#nullable()} is
 * {@code true} and a loader returns {@code null}, the chain stores
 * {@link NullMarker#instance()} in every tier instead of {@code null}.
 * On read, a tier that returns the marker is treated as a hit whose
 * logical value is {@code null}: the chain short-circuits the loader,
 * back-fills upper tiers with the marker, and returns
 * {@link Optional#empty()}. When {@code nullable} is {@code false}, a
 * {@code null} loader result is not cached at all.
 *
 * <p><b>Event dispatch.</b> Every {@code HIT}/{@code MISS}/
 * {@code INVALIDATE}/{@code ERROR} observation is published to
 * registered {@link CacheEventListener}s via {@link #publishEvent}.
 * Listener exceptions are caught and swallowed (with a best-effort log
 * on {@code stderr}) so that a misbehaving listener cannot abort the
 * triggering cache operation — see the contract on
 * {@link CacheEventListener}.
 *
 * <p><b>Mutation semantics.</b> The {@link #append}, {@link #insertAfter}
 * and {@link #remove} operations mutate the chain in place and return
 * {@code this} for fluent composition. The internal tier list is a
 * {@link CopyOnWriteArrayList} so that concurrent reads during a
 * topology change remain consistent without explicit locking.
 *
 * <p><b>Thread safety.</b> All operations are safe for concurrent use
 * by multiple threads. The tier list uses copy-on-write semantics;
 * event listener registration uses a {@code CopyOnWriteArrayList} too.
 * Per-call state (local variables, the read-through walk) is confined
 * to the calling thread.
 *
 * <p><b>Resource lifecycle.</b> {@link #close()} invokes
 * {@link Cache#close()} on every tier in the chain. Tiers removed via
 * {@link #remove} are NOT closed by the chain — the caller retains
 * ownership of the removed tier and is responsible for releasing its
 * resources.
 */
public final class DefaultCacheChain implements CacheChain {

    /** Logical name of this chain; exposed via {@link #name()}. */
    private static final String NAME = "chain";

    /** The ordered tier list (index 0 = L1, last index = farthest tier). */
    private final List<Cache> levels;

    /** Registered event listeners; copy-on-write for safe traversal. */
    private final List<CacheEventListener> listeners;

    /**
     * Coalesces concurrent load requests for the same key onto a single
     * loader invocation, preventing cache stampede when many threads request
     * the same absent key simultaneously.
     */
    private final SingleFlightLoader<Object, Object> singleFlight = new SingleFlightLoader<>();

    /**
     * Constructs a chain with the given initial tiers. The list is
     * copied defensively so that subsequent mutations of the argument
     * by the caller do not affect the chain.
     *
     * @param caches the initial tiers in lookup order; must not be
     *               {@code null} or empty
     * @throws NullPointerException if {@code caches} is {@code null} or
     *         contains a {@code null} element
     * @throws IllegalArgumentException if {@code caches} is empty
     */
    public DefaultCacheChain(Cache... caches) {
        this(List.of(Objects.requireNonNull(caches, "caches")));
    }

    /**
     * Constructs a chain with the given initial tiers. The list is
     * copied defensively so that subsequent mutations of the argument
     * by the caller do not affect the chain.
     *
     * @param caches the initial tiers in lookup order; must not be
     *               {@code null} or empty
     * @throws NullPointerException if {@code caches} is {@code null} or
     *         contains a {@code null} element
     * @throws IllegalArgumentException if {@code caches} is empty
     */
    public DefaultCacheChain(List<Cache> caches) {
        Objects.requireNonNull(caches, "caches");
        if (caches.isEmpty()) {
            throw new IllegalArgumentException("caches must not be empty");
        }
        // Defensive copy + null-element check; CopyOnWriteArrayList gives
        // us safe traversal under concurrent mutation.
        List<Cache> copy = new ArrayList<>(caches.size());
        for (Cache c : caches) {
            copy.add(Objects.requireNonNull(c, "cache element"));
        }
        this.levels = new CopyOnWriteArrayList<>(copy);
        this.listeners = new CopyOnWriteArrayList<>();
    }

    // ──────────────────────────────────────────────────────────────────
    // Cache interface: identity
    // ──────────────────────────────────────────────────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CacheLevel level() {
        // The chain itself has no single level; return the head tier's
        // level so that downstream consumers (events, metrics) see a
        // sensible value. Fallback to L1 if the chain becomes empty
        // after a remove().
        Cache head = peekFirst();
        return head == null ? CacheLevel.L1 : head.level();
    }

    // ──────────────────────────────────────────────────────────────────
    // Cache interface: single-key read
    // ──────────────────────────────────────────────────────────────────

    @Override
    public <K, V> Optional<V> get(K key, TypeReference<V> type) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        // Cache size to avoid repeated volatile reads on CopyOnWriteArrayList
        int size = levels.size();
        for (int i = 0; i < size; i++) {
            Cache tier = levels.get(i);
            Optional<V> cached = tier.get(key, type);
            if (cached.isPresent()) {
                V value = cached.get();
                if (hasListeners()) publishEvent(CacheEvent.of(
                    CacheEventType.HIT, tier.name(), tier.level(), key, value));
                // Back-fill tiers above this hit (indices 0..i-1).
                backFillAbove(key, value, i, null);
                // NullMarker hit: logical value is null, return empty.
                if (NullMarker.isNullMarker(value)) {
                    return Optional.empty();
                }
                return Optional.of(value);
            }
            if (hasListeners()) publishEvent(CacheEvent.of(
                CacheEventType.MISS, tier.name(), tier.level(), key, null));
        }
        // No cache-tier held the entry. The plain get() (without loader)
        // returns empty for a fully-absent key.
        return Optional.empty();
    }

    /**
     * Read-through variant of {@link #get}: if the key is absent or expired,
     * the {@code loader} is invoked to compute the value, which is then cached
     * under {@code policy} before being returned.
     *
     * <p><strong>Checked Exception Constraint.</strong>
     * The {@code loader} parameter uses {@link java.util.function.Supplier},
     * which does not allow throwing checked exceptions (only {@link RuntimeException}
     * and {@link Error}). If your loader needs to handle checked exceptions
     * (e.g., {@link java.sql.SQLException} or {@link java.io.IOException}),
     * you must either:
     * <ul>
     *   <li>Wrap the checked exception in a {@link RuntimeException} or
     *       {@link CacheLoadException} before throwing it from the loader</li>
     *   <li>Handle the checked exception internally in the loader and return
     *       a default value or {@code null}</li>
     * </ul>
     *
     * <p><strong>SingleFlight Behavior.</strong>
     * When multiple threads concurrently request the same absent key, this
     * implementation uses {@link SingleFlightLoader} to coalesce the requests
     * onto a single loader invocation (preventing cache stampede). The winner
     * thread executes the loader; all waiting threads share the result (or
     * exception). Exception propagation follows these rules:
     * <ul>
     *   <li>{@link RuntimeException} and {@link Error}: Propagated to all
     *       waiting threads wrapped in {@link CacheLoadException}</li>
     *   <li>{@code null} result: Cached only if {@link CachePolicy#nullable()}
     *       is {@code true} (using a {@link NullMarker} sentinel)</li>
     * </ul>
     *
     * <p><strong>Error Event Publishing.</strong>
     * Only the winner thread (the one that actually executes the loader)
     * publishes a {@link CacheEventType#ERROR} event via
     * {@link #publishEvent}. Waiting threads that receive the exception via
     * {@link CacheLoadException} do NOT re-publish error events, avoiding
     * duplicate event notifications.
     *
     * @param key    the cache key; must not be {@code null}
     * @param type   the expected value type; must not be {@code null}
     * @param loader the value supplier invoked on miss; must not throw
     *               checked exceptions (see above constraint)
     * @param policy the policy governing the cached entry; must not be {@code null}
     * @param <K>    key type
     * @param <V>    value type
     * @return the cached or freshly loaded value, wrapped in
     *         {@link Optional#empty()} if both the cache and the loader
     *         yielded {@code null} (only possible when
     *         {@link CachePolicy#nullable()} is {@code true})
     * @throws CacheLoadException if the loader throws a {@link RuntimeException}
     *         or {@link Error}; the original exception is preserved as the cause
     */
    @Override
    public <K, V> Optional<V> get(K key,
                                  TypeReference<V> type,
                                  Supplier<V> loader,
                                  CachePolicy policy) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(policy, "policy");

        // Cache size to avoid repeated volatile reads on CopyOnWriteArrayList
        int size = levels.size();
        // Walk the chain looking for a hit.
        for (int i = 0; i < size; i++) {
            Cache tier = levels.get(i);
            Optional<V> cached = tier.get(key, type);
            if (cached.isPresent()) {
                V value = cached.get();
                if (hasListeners()) publishEvent(CacheEvent.of(
                    CacheEventType.HIT, tier.name(), tier.level(), key, value));
                backFillAbove(key, value, i, policy);
                // NullMarker hit: logical value is null, do NOT invoke loader.
                if (NullMarker.isNullMarker(value)) {
                    return Optional.empty();
                }
                return Optional.of(value);
            }
            if (hasListeners()) publishEvent(CacheEvent.of(
                CacheEventType.MISS, tier.name(), tier.level(), key, null));
        }

        // All tiers missed: invoke the loader via single-flight to prevent
        // cache stampede. Only the winner thread executes the loader; all
        // concurrent waiters share its result (or failure).
        V loaded;
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            V result = (V) singleFlight.load((Object) key, () -> {
                try {
                    return loader.get();
                } catch (RuntimeException | Error ex) {
                    // Publish ERROR only by the winner thread (the one that
                    // actually executes the loader). Waiters receive the
                    // exception via CacheLoadException and do NOT re-publish.
                    // Checked exceptions cannot be declared by Supplier#get(),
                    // but SingleFlightLoader already defensively wraps any
                    // Throwable that escapes the loader.
                    Cache head = peekFirst();
                    String headName = head == null ? NAME : head.name();
                    CacheLevel headLevel = head == null ? CacheLevel.L1 : head.level();
                    if (hasListeners()) publishEvent(
                        CacheEvent.error(headName, headLevel, key, ex));
                    throw ex;
                }
            });
            loaded = result;
        } catch (CacheLoadException ex) {
            throw ex;
        }

        // Loader succeeded; decide whether to cache the result.
        if (loaded == null) {
            if (policy.nullable()) {
                // Cache a NullMarker in every tier to absorb repeat misses.
                putAllTiers(key, NullMarker.instance(), policy);
            }
            // nullable=false: do not cache null.
            return Optional.empty();
        }
        // Non-null value: cache it in every tier.
        putAllTiers(key, loaded, policy);
        return Optional.of(loaded);
    }

    // ──────────────────────────────────────────────────────────────────
    // Cache interface: bulk read
    // ──────────────────────────────────────────────────────────────────

    @Override
    public <K, V> Map<K, V> getAll(Set<K> keys, TypeReference<V> type) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(type, "type");
        Map<K, V> result = new LinkedHashMap<>();
        Set<K> remaining = new LinkedHashSet<>(keys);
        for (Cache tier : levels) {
            if (remaining.isEmpty()) break;
            Map<K, V> found = tier.getAll(remaining, type);
            if (!found.isEmpty()) {
                for (Map.Entry<K, V> e : found.entrySet()) {
                    K k = e.getKey();
                    V v = e.getValue();
                    if (hasListeners()) publishEvent(CacheEvent.of(
                        CacheEventType.HIT, tier.name(), tier.level(), k, v));
                    // NullMarker hit: logical value is null.
                    V normalized = NullMarker.isNullMarker(v) ? null : v;
                    result.put(k, normalized);
                    remaining.remove(k);
                }
            }
        }
        return result;
    }

    @Override
    public <K, V> Map<K, V> getAll(Set<K> keys,
                                   TypeReference<V> type,
                                   Function<Set<K>, Map<K, V>> batchLoader,
                                   CachePolicy policy) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(batchLoader, "batchLoader");
        Objects.requireNonNull(policy, "policy");

        Map<K, V> result = new LinkedHashMap<>();
        Set<K> remaining = new LinkedHashSet<>(keys);

        // Cache size to avoid repeated volatile reads on CopyOnWriteArrayList
        int size = levels.size();
        // Walk the chain collecting hits; remember per-tier hit index so
        // we can back-fill the upper tiers only with the entries that
        // actually hit at a deeper level.
        for (int i = 0; i < size; i++) {
            if (remaining.isEmpty()) break;
            Cache tier = levels.get(i);
            Map<K, V> found = tier.getAll(remaining, type);
            if (found.isEmpty()) {
                continue;
            }
            for (Map.Entry<K, V> e : found.entrySet()) {
                K k = e.getKey();
                V v = e.getValue();
                if (hasListeners()) publishEvent(CacheEvent.of(
                    CacheEventType.HIT, tier.name(), tier.level(), k, v));
                if (NullMarker.isNullMarker(v)) {
                    result.put(k, null);
                } else {
                    result.put(k, v);
                }
                remaining.remove(k);
                // Back-fill tiers above i with the hit value (also a
                // NullMarker if that is what we found).
                backFillAbove(k, v, i, policy);
            }
        }

        if (remaining.isEmpty()) {
            return result;
        }

        // Invoke the batch loader for the still-missing keys.
        Map<K, V> loaded;
        try {
            loaded = batchLoader.apply(remaining);
        } catch (RuntimeException | Error ex) {
            Cache head = peekFirst();
            String headName = head == null ? NAME : head.name();
            CacheLevel headLevel = head == null ? CacheLevel.L1 : head.level();
            if (hasListeners()) publishEvent(CacheEvent.error(headName, headLevel, remaining, ex));
            if (ex instanceof CacheLoadException cle) {
                throw cle;
            }
            throw new CacheLoadException(
                "Cache batch load failed for " + remaining.size() + " keys", ex);
        }
        Objects.requireNonNull(loaded, "batchLoader returned null");

        // Cache each loaded entry in every tier, then add it to the result.
        // For keys the batch loader omitted, fall back to NullMarker when
        // policy.nullable is true (negative caching); otherwise leave them
        // absent from the result entirely.
        Map<K, V> toBackFill = new LinkedHashMap<>();
        for (K k : remaining) {
            V v = loaded.get(k);
            if (v == null) {
                if (policy.nullable()) {
                    @SuppressWarnings("unchecked") // NullMarker stands in for V as a null sentinel
                    V marker = (V) NullMarker.instance();
                    toBackFill.put(k, marker);
                    result.put(k, null);
                }
                // nullable=false: do not cache, do not include.
            } else {
                toBackFill.put(k, v);
                result.put(k, v);
            }
        }
        if (!toBackFill.isEmpty()) {
            putAllTiers(toBackFill, policy);
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────────────
    // Cache interface: writes & invalidation
    // ──────────────────────────────────────────────────────────────────

    @Override
    public <K, V> void put(K key, V value, CachePolicy policy) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(policy, "policy");
        putAllTiers(key, value, policy);
    }

    @Override
    public <K, V> void putAll(Map<K, V> entries, CachePolicy policy) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(policy, "policy");
        if (entries.isEmpty()) {
            return;
        }
        putAllTiers(entries, policy);
    }

    @Override
    public <K> void invalidate(K key) {
        Objects.requireNonNull(key, "key");
        for (Cache tier : levels) {
            tier.invalidate(key);
            if (hasListeners()) publishEvent(CacheEvent.of(
                CacheEventType.INVALIDATE, tier.name(), tier.level(), key, null));
        }
    }

    @Override
    public <K> void invalidateAll(Set<K> keys) {
        Objects.requireNonNull(keys, "keys");
        if (keys.isEmpty()) {
            return;
        }
        for (Cache tier : levels) {
            tier.invalidateAll(keys);
            // Bulk-invalidate event carries null as the key (per the
            // CacheChain contract — one event per tier, not per key).
            if (hasListeners()) publishEvent(CacheEvent.of(
                CacheEventType.INVALIDATE, tier.name(), tier.level(), null, null));
        }
    }

    @Override
    public void invalidateAll() {
        for (Cache tier : levels) {
            tier.invalidateAll();
            if (hasListeners()) publishEvent(CacheEvent.of(
                CacheEventType.INVALIDATE, tier.name(), tier.level(), null, null));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Cache interface: stats & lifecycle
    // ──────────────────────────────────────────────────────────────────

    @Override
    public CacheStats stats() {
        long hits = 0L;
        long misses = 0L;
        long evictions = 0L;
        long expirations = 0L;
        long loads = 0L;
        long loadFailures = 0L;
        long estimatedSize = 0L;
        long totalLoadNanos = 0L;

        for (Cache tier : levels) {
            CacheStats s = tier.stats();
            hits += s.hits();
            misses += s.misses();
            evictions += s.evictions();
            expirations += s.expirations();
            loads += s.loads();
            loadFailures += s.loadFailures();
            estimatedSize += s.estimatedSize();
            // Sum up total load time using loads * averageLoadTime; guard
            // against the empty-stats case where averageLoadTime is ZERO.
            totalLoadNanos += s.loads() * s.averageLoadTime().toNanos();
        }
        Duration avgLoadTime = loads > 0L
            ? Duration.ofNanos(totalLoadNanos / loads)
            : Duration.ZERO;
        return new CacheStats(hits, misses, evictions, expirations,
            loads, loadFailures, avgLoadTime, estimatedSize);
    }

    @Override
    public void close() {
        for (Cache tier : levels) {
            try {
                tier.close();
            } catch (RuntimeException ex) {
                // Continue closing the remaining tiers; surface the first
                // failure as a published event so the caller is aware.
                if (hasListeners()) publishEvent(CacheEvent.error(
                    tier.name(), tier.level(), null, ex));
            }
        }
        singleFlight.close();
    }

    // ──────────────────────────────────────────────────────────────────
    // CacheChain interface: tier management
    // ──────────────────────────────────────────────────────────────────

    @Override
    public List<Cache> levels() {
        // Snapshot: callers cannot mutate the internal list through the
        // returned reference.
        return List.copyOf(levels);
    }

    @Override
    public DefaultCacheChain append(Cache cache) {
        Objects.requireNonNull(cache, "cache");
        levels.add(cache);
        return this;
    }

    @Override
    public DefaultCacheChain insertAfter(CacheLevel level, Cache cache) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(cache, "cache");
        int insertAt = 0;
        boolean found = false;
        // Cache size to avoid repeated volatile reads
        int size = levels.size();
        for (int i = 0; i < size; i++) {
            if (levels.get(i).level() == level) {
                insertAt = i + 1;
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalArgumentException(
                "Target CacheLevel '" + level + "' not found in chain; "
                    + "callers must ensure the target level is present before calling insertAfter");
        }
        levels.add(insertAt, cache);
        return this;
    }

    @Override
    public DefaultCacheChain remove(CacheLevel level) {
        Objects.requireNonNull(level, "level");
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i).level() == level) {
                levels.remove(i);
                break; // remove only the first matching tier
            }
        }
        return this;
    }

    // ──────────────────────────────────────────────────────────────────
    // Event dispatch (DefaultCacheChain-specific, not part of Cache)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Registers a {@link CacheEventListener} to receive all subsequent
     * events published by this chain. The listener is appended to the
     * registered set and invoked in registration order.
     *
     * <p>This method is NOT part of the {@link Cache} SPI; it is specific
     * to {@code DefaultCacheChain} because the chain is the natural event
     * bus for cross-tier observations. Per-tier listeners can be wired
     * directly on the underlying {@link Cache} implementation when
     * supported.
     *
     * @param listener the listener to register; must not be {@code null}
     * @return this chain, for fluent registration
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public DefaultCacheChain addEventListener(CacheEventListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return this;
    }

    /**
     * Returns {@code true} when at least one listener is registered. Hot-path
     * callers use this to skip {@link CacheEvent} construction entirely when
     * no listeners are attached, avoiding unnecessary short-lived object
     * allocation on every cache hit/miss.
     */
    private boolean hasListeners() {
        return !listeners.isEmpty();
    }

    /**
     * Publishes an event to all registered listeners. Listener exceptions
     * are caught and reported to {@code stderr} so that a misbehaving
     * listener cannot abort the triggering cache operation — see the
     * failure-isolation contract on {@link CacheEventListener}.
     *
     * @param event the event to publish; must not be {@code null}
     */
    void publishEvent(CacheEvent event) {
        Objects.requireNonNull(event, "event");
        for (CacheEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException | Error ex) {
                // Best-effort log to stderr; do not propagate so the
                // cache operation continues. SLF4J is available in the
                // module but we avoid taking a logger dependency in the
                // hot path of event dispatch.
                System.err.println(
                    "[DefaultCacheChain] listener threw on event " + event
                        + ": " + ex);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Internals
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns the first tier of the chain, or {@code null} if the chain
     * is currently empty (only possible after a {@link #remove} that
     * dropped the last tier).
     */
    private Cache peekFirst() {
        return levels.isEmpty() ? null : levels.get(0);
    }

    /**
     * Back-fills the tiers above index {@code hitIndex} with the given
     * value. Tiers {@code 0 .. hitIndex-1} receive a {@code put}; the
     * hit tier itself and tiers below are left untouched (they already
     * have the value).
     *
     * @param key the cache key
     * @param value the value to back-fill
     * @param hitIndex the index of the tier that had the hit
     * @param policy the cache policy to use; if {@code null}, uses the default back-fill policy
     */
    private <K, V> void backFillAbove(K key, V value, int hitIndex, CachePolicy policy) {
        if (hitIndex <= 0) {
            return;
        }
        // Use the caller-supplied policy when available to maintain TTL/nullable
        // consistency; fall back to the default policy for plain get() calls
        // that don't have an explicit policy.
        CachePolicy effectivePolicy = policy != null ? policy : defaultBackFillPolicy();
        for (int i = 0; i < hitIndex; i++) {
            levels.get(i).put(key, value, effectivePolicy);
        }
    }

    /**
     * Writes the given key/value to every tier in the chain, in order
     * from L1 to L3 (closest first).
     */
    private <K, V> void putAllTiers(K key, V value, CachePolicy policy) {
        for (Cache tier : levels) {
            tier.put(key, value, policy);
        }
    }

    /**
     * Writes the given entries to every tier in the chain, in order
     * from L1 to L3 (closest first).
     */
    private <K, V> void putAllTiers(Map<K, V> entries, CachePolicy policy) {
        for (Cache tier : levels) {
            tier.putAll(entries, policy);
        }
    }

    /**
     * Lazily-cached default policy for back-fill operations. The policy
     * is the {@link CachePolicy#builder()} default, which is tuned for
     * the common HORM read-through scenario. Held in a static nested class
     * so the JVM class-loading guarantee provides thread-safe lazy init
     * without explicit synchronization.
     */
    private static final class DefaultBackFillPolicyHolder {
        static final CachePolicy INSTANCE = CachePolicy.builder().build();
    }

    private static CachePolicy defaultBackFillPolicy() {
        return DefaultBackFillPolicyHolder.INSTANCE;
    }
}
