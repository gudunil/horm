package com.holo.framework.horm.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Minimal in-process {@link Cache} implementation used by
 * {@link DefaultCacheChainTest} to exercise the chain without depending
 * on Caffeine, Redisson or any other production provider.
 *
 * <p>The cache is a thin wrapper around a {@link HashMap} with the
 * following instrumentation features that the tests rely on:
 * <ul>
 *   <li>Every {@code get}/{@code put}/{@code invalidate}/{@code getAll}
 *       call is appended to a recorded list, so tests can assert on the
 *       exact sequence of operations the chain performed against each
 *       tier.</li>
 *   <li>{@code hit} and {@code miss} counters mirror the
 *       {@link CacheStats} contract, so {@link #stats()} returns a
 *       non-trivial snapshot that {@code DefaultCacheChain#stats()} can
 *       aggregate.</li>
 *   <li>The {@code name} and {@code level} are configurable at
 *       construction so a chain can be assembled with distinguishable
 *       tiers ({@code "l1"}, {@code "l2"}, etc.).</li>
 *   <li>{@code close()} flips a flag that tests can assert on; the
 *       cache is otherwise still functional after close to keep the
 *       test fixtures permissive.</li>
 * </ul>
 *
 * <p><b>Thread safety.</b> {@code TestCache} is <em>not</em> thread-safe.
 * Tests are single-threaded by default in the cache module, so this
 * keeps the fixture minimal. Production code should not depend on this
 * class.
 *
 * <p><b>NullMarker.</b> The cache transparently stores
 * {@link NullMarker} values written by the chain; it does not interpret
 * them, so the chain's null-caching logic is exercised end-to-end
 * through this fixture.
 */
class TestCache implements Cache {

    /** Operation kinds recorded by the cache; see {@link Op}. */
    enum OpKind {
        GET, GET_WITH_LOADER, GET_ALL, PUT, PUT_ALL,
        INVALIDATE, INVALIDATE_ALL_SET, INVALIDATE_ALL, CLOSE
    }

    /** A single recorded operation against the cache. */
    static final class Op {
        final OpKind kind;
        final Object key;
        final Object value;

        Op(OpKind kind, Object key, Object value) {
            this.kind = kind;
            this.key = key;
            this.value = value;
        }

        @Override
        public String toString() {
            return kind + "(key=" + key + ", value=" + value + ")";
        }
    }

    private final String name;
    private final CacheLevel level;
    private final Map<Object, Object> store = new HashMap<>();
    private final List<Op> ops = new ArrayList<>();

    private long hits;
    private long misses;
    private long loads;
    private long loadFailures;
    private boolean closed;

    /** Creates a new test cache with the given identity. */
    TestCache(String name, CacheLevel level) {
        this.name = Objects.requireNonNull(name, "name");
        this.level = Objects.requireNonNull(level, "level");
    }

    // ── accessors for test assertions ──────────────────────────────────

    /** Returns the recorded operations against this cache, in order. */
    List<Op> ops() {
        return List.copyOf(ops);
    }

    /** Returns the live store; tests may inspect directly to assert state. */
    Map<Object, Object> store() {
        return store;
    }

    /** Returns whether the cache currently holds the given key. */
    boolean contains(Object key) {
        return store.containsKey(key);
    }

    /** Returns the raw stored value (no removal, no NullMarker translation). */
    Object raw(Object key) {
        return store.get(key);
    }

    /** Returns the recorded hit count. */
    long hits() {
        return hits;
    }

    /** Returns the recorded miss count. */
    long misses() {
        return misses;
    }

    /** Returns the recorded load count. */
    long loads() {
        return loads;
    }

    /** Returns the recorded load-failure count. */
    long loadFailures() {
        return loadFailures;
    }

    /** Returns whether {@link #close()} has been invoked. */
    boolean closed() {
        return closed;
    }

    /** Directly seeds the cache with a value, bypassing the recorded ops. */
    void seed(Object key, Object value) {
        store.put(key, value);
    }

    // ── Cache interface ─────────────────────────────────────────────────

    @Override
    public String name() {
        return name;
    }

    @Override
    public CacheLevel level() {
        return level;
    }

    @Override
    public <K, V> Optional<V> get(K key, TypeReference<V> type) {
        ops.add(new Op(OpKind.GET, key, null));
        if (store.containsKey(key)) {
            hits++;
            @SuppressWarnings("unchecked")
            V value = (V) store.get(key);
            return Optional.ofNullable(value);
        }
        misses++;
        return Optional.empty();
    }

    @Override
    public <K, V> Optional<V> get(K key,
                                  TypeReference<V> type,
                                  Supplier<V> loader,
                                  CachePolicy policy) {
        ops.add(new Op(OpKind.GET_WITH_LOADER, key, null));
        if (store.containsKey(key)) {
            hits++;
            @SuppressWarnings("unchecked")
            V value = (V) store.get(key);
            return Optional.ofNullable(value);
        }
        misses++;
        loads++;
        try {
            V loaded = loader.get();
            if (loaded == null) {
                if (policy.nullable()) {
                    store.put(key, NullMarker.instance());
                }
                return Optional.empty();
            }
            store.put(key, loaded);
            return Optional.of(loaded);
        } catch (RuntimeException | Error ex) {
            loadFailures++;
            throw ex;
        }
    }

    @Override
    public <K, V> Map<K, V> getAll(Set<K> keys, TypeReference<V> type) {
        ops.add(new Op(OpKind.GET_ALL, keys, null));
        Map<K, V> result = new LinkedHashMap<>();
        for (K key : keys) {
            if (store.containsKey(key)) {
                hits++;
                @SuppressWarnings("unchecked")
                V value = (V) store.get(key);
                result.put(key, value);
            } else {
                misses++;
            }
        }
        return result;
    }

    @Override
    public <K, V> void put(K key, V value, CachePolicy policy) {
        ops.add(new Op(OpKind.PUT, key, value));
        store.put(key, value);
    }

    @Override
    public <K, V> void putAll(Map<K, V> entries, CachePolicy policy) {
        ops.add(new Op(OpKind.PUT_ALL, entries, null));
        store.putAll(entries);
    }

    @Override
    public <K> void invalidate(K key) {
        ops.add(new Op(OpKind.INVALIDATE, key, null));
        store.remove(key);
    }

    @Override
    public <K> void invalidateAll(Set<K> keys) {
        ops.add(new Op(OpKind.INVALIDATE_ALL_SET, keys, null));
        for (K key : keys) {
            store.remove(key);
        }
    }

    @Override
    public void invalidateAll() {
        ops.add(new Op(OpKind.INVALIDATE_ALL, null, null));
        store.clear();
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(
            hits, misses, 0L, 0L, loads, loadFailures,
            java.time.Duration.ZERO, store.size());
    }

    @Override
    public void close() {
        ops.add(new Op(OpKind.CLOSE, null, null));
        closed = true;
    }
}
