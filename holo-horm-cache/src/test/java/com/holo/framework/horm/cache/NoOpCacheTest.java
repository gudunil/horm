package com.holo.framework.horm.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link NoOpCache}.
 *
 * <p>Coverage focus:
 * <ul>
 *   <li>singleton invariant of {@link NoOpCache#instance()};</li>
 *   <li>identity contracts of {@link #name()} and {@link #level()};</li>
 *   <li>read semantics: {@code get} always misses, {@code get} with
 *       loader delegates to the loader, propagates null and exceptions;</li>
 *   <li>bulk read: {@link #getAll(Set, TypeReference)} returns an empty map;</li>
 *   <li>write/invalidation methods are silent no-ops;</li>
 *   <li>{@link #stats()} returns the canonical {@link CacheStats#empty()};</li>
 *   <li>{@link #close()} is idempotent and exception-free;</li>
 *   <li>concurrent {@link NoOpCache#instance()} calls observe the same singleton.</li>
 * </ul>
 *
 * <p>The tests deliberately avoid Mockito — loader invocation counts are
 * tracked with an {@link AtomicInteger} so the assertions stay
 * self-contained and deterministic.
 */
class NoOpCacheTest {

    /** Default policy reused across read-through assertions. */
    private static final CachePolicy POLICY = CachePolicy.builder().build();

    /** A string {@link TypeReference} capturing {@code String}. */
    private static final TypeReference<String> STRING_TYPE =
        new TypeReference<String>() {};

    /** An integer {@link TypeReference} capturing {@code Integer}. */
    private static final TypeReference<Integer> INT_TYPE =
        new TypeReference<Integer>() {};

    // ── singleton ──────────────────────────────────────────────────────

    @Test
    @DisplayName("instance() returns the same reference on every call")
    void instanceReturnsSameReference() {
        NoOpCache first = NoOpCache.instance();
        NoOpCache second = NoOpCache.instance();

        assertThat(first).isSameAs(second);
        assertThat(first).isSameAs(NoOpCache.instance());
    }

    // ── identity ───────────────────────────────────────────────────────

    @Test
    @DisplayName("name() is \"noop\"")
    void nameIsNoop() {
        assertThat(NoOpCache.instance().name()).isEqualTo("noop");
    }

    @Test
    @DisplayName("level() is L1")
    void levelIsL1() {
        assertThat(NoOpCache.instance().level()).isEqualTo(CacheLevel.L1);
    }

    // ── get without loader ─────────────────────────────────────────────

    @Test
    @DisplayName("get(key, type) always returns Optional.empty()")
    void getWithoutLoaderReturnsEmpty() {
        NoOpCache cache = NoOpCache.instance();

        Optional<String> result = cache.get("any-key", STRING_TYPE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("get(key, type) returns empty even after a put()")
    void getStillEmptyAfterPut() {
        NoOpCache cache = NoOpCache.instance();
        cache.put("key", "value", POLICY);

        Optional<String> result = cache.get("key", STRING_TYPE);

        assertThat(result).isEmpty();
    }

    // ── get with loader ────────────────────────────────────────────────

    @Test
    @DisplayName("get(key, type, loader, policy) invokes loader and returns its non-null value")
    void getWithLoaderInvokesLoaderAndReturnsValue() {
        NoOpCache cache = NoOpCache.instance();
        AtomicInteger invocations = new AtomicInteger();
        Supplier<String> loader = () -> {
            invocations.incrementAndGet();
            return "loaded";
        };

        Optional<String> result = cache.get("key", STRING_TYPE, loader, POLICY);

        assertThat(result).contains("loaded");
        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("get(key, type, loader, policy) wraps a null loader result as Optional.empty()")
    void getWithLoaderReturningNullYieldsEmpty() {
        NoOpCache cache = NoOpCache.instance();
        Supplier<String> loader = () -> null;

        Optional<String> result = cache.get("key", STRING_TYPE, loader, POLICY);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("get(key, type, loader, policy) invokes loader on every call (no caching)")
    void getWithLoaderInvokesLoaderEveryTime() {
        NoOpCache cache = NoOpCache.instance();
        AtomicInteger invocations = new AtomicInteger();
        Supplier<String> loader = () -> {
            invocations.incrementAndGet();
            return "v-" + invocations.get();
        };

        cache.get("key", STRING_TYPE, loader, POLICY);
        cache.get("key", STRING_TYPE, loader, POLICY);
        cache.get("key", STRING_TYPE, loader, POLICY);

        assertThat(invocations.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("get(key, type, loader, policy) propagates loader exceptions unchanged")
    void getWithLoaderPropagatesExceptionUnwrapped() {
        NoOpCache cache = NoOpCache.instance();
        IllegalStateException original = new IllegalStateException("boom");
        Supplier<String> loader = () -> {
            throw original;
        };

        assertThatThrownBy(() -> cache.get("key", STRING_TYPE, loader, POLICY))
            .isSameAs(original)
            .isExactlyInstanceOf(IllegalStateException.class);

        // Also assert it is NOT wrapped in CacheLoadException.
        assertThatThrownBy(() -> cache.get("key", STRING_TYPE, loader, POLICY))
            .isNotInstanceOf(CacheLoadException.class);
    }

    @Test
    @DisplayName("get(key, type, loader, policy) propagates checked-runtime exceptions unchanged")
    void getWithLoaderPropagatesRuntimeExceptionSubclasses() {
        NoOpCache cache = NoOpCache.instance();
        IllegalArgumentException original = new IllegalArgumentException("arg");
        Supplier<Integer> loader = () -> {
            throw original;
        };

        assertThatThrownBy(() -> cache.get(42, INT_TYPE, loader, POLICY))
            .isSameAs(original);
    }

    // ── getAll ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getAll(keys, type) returns an empty map")
    void getAllReturnsEmptyMap() {
        NoOpCache cache = NoOpCache.instance();

        Map<String, String> result =
            cache.getAll(Set.of("a", "b", "c"), STRING_TYPE);

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("getAll(keys, type) returns an empty map even after putAll()")
    void getAllEmptyAfterPutAll() {
        NoOpCache cache = NoOpCache.instance();
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("a", "1");
        entries.put("b", "2");
        cache.putAll(entries, POLICY);

        Map<String, String> result = cache.getAll(entries.keySet(), STRING_TYPE);

        assertThat(result).isEmpty();
    }

    // ── write / invalidate no-ops ──────────────────────────────────────

    @Test
    @DisplayName("put() is a no-op and throws nothing")
    void putIsNoOp() {
        NoOpCache cache = NoOpCache.instance();
        // The assertion is simply that no exception is thrown.
        cache.put("k", "v", POLICY);
        cache.put(null, "v", POLICY);
        cache.put("k", null, POLICY);
    }

    @Test
    @DisplayName("putAll() is a no-op and throws nothing")
    void putAllIsNoOp() {
        NoOpCache cache = NoOpCache.instance();
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("a", "1");
        entries.put("b", "2");

        cache.putAll(entries, POLICY);
        cache.putAll(Map.of(), POLICY);
    }

    @Test
    @DisplayName("invalidate(key) is a no-op and throws nothing")
    void invalidateIsNoOp() {
        NoOpCache cache = NoOpCache.instance();
        cache.invalidate("any-key");
        cache.invalidate(null);
    }

    @Test
    @DisplayName("invalidateAll(Set) is a no-op and throws nothing")
    void invalidateAllSetIsNoOp() {
        NoOpCache cache = NoOpCache.instance();
        cache.invalidateAll(Set.of("a", "b"));
        cache.invalidateAll(Set.of());
    }

    @Test
    @DisplayName("invalidateAll() is a no-op and throws nothing")
    void invalidateAllIsNoOp() {
        NoOpCache cache = NoOpCache.instance();
        cache.invalidateAll();
    }

    @Test
    @DisplayName("mutation methods have no observable effect on subsequent reads")
    void mutationMethodsHaveNoSideEffect() {
        NoOpCache cache = NoOpCache.instance();
        cache.put("k", "v", POLICY);
        cache.putAll(Map.of("k2", "v2"), POLICY);
        cache.invalidate("absent");
        cache.invalidateAll(Set.of("absent"));
        cache.invalidateAll();

        // Reads still return empty — no value leaks through from put().
        assertThat(cache.get("k", STRING_TYPE)).isEmpty();
        assertThat(cache.getAll(Set.of("k", "k2"), STRING_TYPE)).isEmpty();
    }

    // ── stats ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("stats() returns CacheStats.empty()")
    void statsReturnsEmpty() {
        NoOpCache cache = NoOpCache.instance();

        CacheStats stats = cache.stats();

        assertThat(stats).isSameAs(CacheStats.empty());
    }

    @Test
    @DisplayName("stats() reports zero counters across every field")
    void statsHasZeroCounters() {
        CacheStats stats = NoOpCache.instance().stats();

        assertThat(stats.hits()).isZero();
        assertThat(stats.misses()).isZero();
        assertThat(stats.evictions()).isZero();
        assertThat(stats.expirations()).isZero();
        assertThat(stats.loads()).isZero();
        assertThat(stats.loadFailures()).isZero();
        assertThat(stats.averageLoadTime()).isEqualTo(Duration.ZERO);
        assertThat(stats.estimatedSize()).isZero();
    }

    @Test
    @DisplayName("stats() stays at zero after read/write activity")
    void statsStaysZeroAfterActivity() {
        NoOpCache cache = NoOpCache.instance();
        cache.put("k", "v", POLICY);
        cache.get("k", STRING_TYPE);
        cache.get("k", STRING_TYPE, () -> "v", POLICY);
        cache.invalidate("k");

        CacheStats stats = cache.stats();

        assertThat(stats).isSameAs(CacheStats.empty());
        assertThat(stats.hits()).isZero();
        assertThat(stats.misses()).isZero();
        assertThat(stats.loads()).isZero();
    }

    // ── close ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("close() throws nothing")
    void closeThrowsNothing() {
        NoOpCache cache = NoOpCache.instance();
        cache.close();
    }

    @Test
    @DisplayName("close() is idempotent — repeated calls do not throw")
    void closeIsIdempotent() {
        NoOpCache cache = NoOpCache.instance();
        cache.close();
        cache.close();
        cache.close();
    }

    @Test
    @DisplayName("close() does not affect subsequent method calls (singleton still usable)")
    void closeDoesNotBreakSubsequentCalls() {
        NoOpCache cache = NoOpCache.instance();
        cache.close();

        // The singleton must remain usable — close() is a no-op.
        assertThat(cache.get("k", STRING_TYPE)).isEmpty();
        assertThat(cache.name()).isEqualTo("noop");
        assertThat(cache.stats()).isSameAs(CacheStats.empty());
    }

    // ── try-with-resources compatibility ───────────────────────────────

    @Test
    @DisplayName("NoOpCache works inside try-with-resources")
    void worksInTryWithResources() {
        String name;
        try (NoOpCache cache = NoOpCache.instance()) {
            name = cache.name();
        }
        assertThat(name).isEqualTo("noop");
    }

    // ── concurrency ────────────────────────────────────────────────────

    @Test
    @DisplayName("concurrent instance() calls all observe the same singleton")
    void concurrentInstanceReturnsSameSingleton() throws InterruptedException {
        int threadCount = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        // Collect every distinct instance reference observed by workers.
        Set<NoOpCache> observed = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<>());

        try {
            for (int i = 0; i < threadCount; i++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    observed.add(NoOpCache.instance());
                });
            }
            ready.await();
            start.countDown();
            // Give the pool a moment to drain.
            pool.shutdown();
            boolean terminated = pool.awaitTermination(5, TimeUnit.SECONDS);

            assertThat(terminated).as("executor should terminate").isTrue();
            assertThat(observed)
                .as("every thread must observe the same singleton instance")
                .hasSize(1)
                .containsOnly(NoOpCache.instance());
        } finally {
            if (!pool.isTerminated()) {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("concurrent get-with-loader calls each invoke the loader (no shared state)")
    void concurrentGetWithLoaderIsSafe() throws InterruptedException {
        int threadCount = 8;
        int callsPerThread = 50;
        NoOpCache cache = NoOpCache.instance();
        AtomicInteger totalLoads = new AtomicInteger();
        AtomicInteger emptyResults = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        try {
            for (int t = 0; t < threadCount; t++) {
                final int threadIndex = t;
                pool.execute(() -> {
                    for (int i = 0; i < callsPerThread; i++) {
                        final int callIndex = i;
                        Supplier<String> loader = () -> {
                            totalLoads.incrementAndGet();
                            return "v-" + threadIndex + "-" + callIndex;
                        };
                        Optional<String> r = cache.get("k", STRING_TYPE, loader, POLICY);
                        // Loader always returns a non-null string; an empty result
                        // would indicate a contract violation.
                        if (r.isEmpty()) {
                            emptyResults.incrementAndGet();
                        }
                    }
                });
            }
            pool.shutdown();
            boolean terminated = pool.awaitTermination(5, TimeUnit.SECONDS);

            assertThat(terminated).as("executor should terminate").isTrue();
            assertThat(emptyResults.get())
                .as("loader results should never be empty (loader returns non-null)")
                .isZero();
            assertThat(totalLoads.get())
                .as("loader should fire exactly once per call (no caching)")
                .isEqualTo(threadCount * callsPerThread);
        } finally {
            if (!pool.isTerminated()) {
                pool.shutdownNow();
            }
        }
    }

    // ── nested: bulk operations grouped for readability ────────────────

    @Nested
    @DisplayName("bulk operations")
    class BulkOperations {

        @Test
        @DisplayName("getAll on a large key set still returns empty")
        void getAllLargeKeySetReturnsEmpty() {
            Set<Integer> keys = new java.util.HashSet<>();
            for (int i = 0; i < 1000; i++) {
                keys.add(i);
            }

            Map<Integer, Integer> result =
                NoOpCache.instance().getAll(keys, INT_TYPE);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("putAll with a large map is a no-op")
        void putAllLargeMapIsNoOp() {
            Map<Integer, String> entries = new LinkedHashMap<>();
            for (int i = 0; i < 1000; i++) {
                entries.put(i, "v" + i);
            }

            NoOpCache.instance().putAll(entries, POLICY);

            // Assert no read-through side effect: bulk read returns nothing
            // even though we just stored 1000 entries.
            TypeReference<String> stringType = new TypeReference<String>() {};
            Map<Integer, String> after =
                NoOpCache.instance().getAll(entries.keySet(), stringType);
            assertThat(after).isEmpty();
        }
    }
}
