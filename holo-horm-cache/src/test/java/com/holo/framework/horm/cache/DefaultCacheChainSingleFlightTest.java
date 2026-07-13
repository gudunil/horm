package com.holo.framework.horm.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Concurrency tests for the {@link SingleFlightLoader} integration in
 * {@link DefaultCacheChain} (O6 optimization), verifying that concurrent
 * {@code get} calls for the same absent key coalesce onto a single loader
 * invocation (cache stampede prevention).
 *
 * <p>Uses a thread-safe {@link ConcurrentTestCache} instead of
 * {@link TestCache} (which is explicitly not thread-safe) because
 * multiple threads concurrently read and write the cache tiers during
 * the single-flight coalescing window.
 *
 * <p>Synchronization pattern mirrors {@link SingleFlightLoaderTest}:
 * CyclicBarrier to start all threads simultaneously, loaderEntered/
 * loaderProceed latches to control the winner's loader, and
 * passedBarrier polling to ensure every thread has entered
 * {@code singleFlight.load()} before the winner completes.
 */
class DefaultCacheChainSingleFlightTest {

    private static final TypeReference<String> STRING_TYPE = new TypeReference<>() {};

    private static CachePolicy nonNullablePolicy() {
        return CachePolicy.builder().nullable(false).build();
    }

    /**
     * Minimal thread-safe {@link Cache} backed by a
     * {@link ConcurrentHashMap}. Used instead of {@link TestCache} for
     * concurrency tests where multiple threads read and write the same
     * tier simultaneously.
     */
    private static final class ConcurrentTestCache implements Cache {
        private final String name;
        private final CacheLevel level;
        private final ConcurrentHashMap<Object, Object> store = new ConcurrentHashMap<>();

        ConcurrentTestCache(String name, CacheLevel level) {
            this.name = Objects.requireNonNull(name, "name");
            this.level = Objects.requireNonNull(level, "level");
        }

        void seed(Object key, Object value) {
            store.put(key, value);
        }

        @Override
        public String name() { return name; }

        @Override
        public CacheLevel level() { return level; }

        @Override
        public <K, V> Optional<V> get(K key, TypeReference<V> type) {
            Object v = store.get(key);
            if (v == null) return Optional.empty();
            @SuppressWarnings("unchecked")
            V value = (V) v;
            return Optional.ofNullable(value);
        }

        @Override
        public <K, V> Optional<V> get(K key, TypeReference<V> type,
                                      Supplier<V> loader, CachePolicy policy) {
            throw new UnsupportedOperationException("not used by DefaultCacheChain.get");
        }

        @Override
        public <K, V> Map<K, V> getAll(Set<K> keys, TypeReference<V> type) {
            throw new UnsupportedOperationException("not used by these tests");
        }

        @Override
        public <K, V> void put(K key, V value, CachePolicy policy) {
            store.put(key, value);
        }

        @Override
        public <K, V> void putAll(Map<K, V> entries, CachePolicy policy) {
            store.putAll(entries);
        }

        @Override
        public <K> void invalidate(K key) {
            store.remove(key);
        }

        @Override
        public <K> void invalidateAll(Set<K> keys) {
            for (K key : keys) store.remove(key);
        }

        @Override
        public void invalidateAll() {
            store.clear();
        }

        @Override
        public CacheStats stats() {
            return CacheStats.empty();
        }

        @Override
        public void close() {
            store.clear();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 1. Concurrent gets for same key invoke loader exactly once
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Timeout(30)
    void concurrentGetsForSameKeyInvokeLoaderOnce() throws Exception {
        ConcurrentTestCache l1 = new ConcurrentTestCache("l1", CacheLevel.L1);
        ConcurrentTestCache l2 = new ConcurrentTestCache("l2", CacheLevel.L2);
        DefaultCacheChain chain = new DefaultCacheChain(List.of(l1, l2));

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier allAtGetCall = new CyclicBarrier(threadCount);
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch loaderProceed = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        AtomicInteger passedBarrier = new AtomicInteger();

        try {
            List<Future<String>> futures = new ArrayList<>(threadCount);
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    allAtGetCall.await();
                    passedBarrier.incrementAndGet();
                    return chain.get("same-key", STRING_TYPE, () -> {
                        invocations.incrementAndGet();
                        loaderEntered.countDown();
                        try {
                            loaderProceed.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return "value";
                    }, nonNullablePolicy()).orElse(null);
                }));
            }

            ready.await();
            start.countDown();
            assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (passedBarrier.get() < threadCount) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError(
                        "Timed out waiting for threads to pass barrier: "
                            + passedBarrier.get() + "/" + threadCount);
                }
                Thread.sleep(5);
            }
            Thread.sleep(20);
            loaderProceed.countDown();

            for (Future<String> f : futures) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo("value");
            }
            assertThat(invocations.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. Loader failure propagates to all waiters as CacheLoadException
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Timeout(30)
    void loaderFailurePropagatedToAllWaitersAsCacheLoadException() throws Exception {
        ConcurrentTestCache l1 = new ConcurrentTestCache("l1", CacheLevel.L1);
        ConcurrentTestCache l2 = new ConcurrentTestCache("l2", CacheLevel.L2);
        DefaultCacheChain chain = new DefaultCacheChain(List.of(l1, l2));

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier allAtGetCall = new CyclicBarrier(threadCount);
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch loaderProceed = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        AtomicInteger passedBarrier = new AtomicInteger();

        IllegalStateException sharedCause = new IllegalStateException("boom");

        try {
            List<Future<String>> futures = new ArrayList<>(threadCount);
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    allAtGetCall.await();
                    passedBarrier.incrementAndGet();
                    try {
                        return chain.get("fail-key", STRING_TYPE, () -> {
                            invocations.incrementAndGet();
                            loaderEntered.countDown();
                            try {
                                loaderProceed.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                            throw sharedCause;
                        }, nonNullablePolicy()).orElse(null);
                    } catch (CacheLoadException ex) {
                        throw ex;
                    }
                }));
            }

            ready.await();
            start.countDown();
            assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (passedBarrier.get() < threadCount) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError(
                        "Timed out waiting for threads to pass barrier: "
                            + passedBarrier.get() + "/" + threadCount);
                }
                Thread.sleep(5);
            }
            Thread.sleep(20);
            loaderProceed.countDown();

            for (Future<String> f : futures) {
                assertThatThrownBy(() -> f.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(CacheLoadException.class);
            }
            assertThat(invocations.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. Different keys do not block each other
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Timeout(10)
    void differentKeysDoNotBlockEachOther() throws Exception {
        ConcurrentTestCache l1 = new ConcurrentTestCache("l1", CacheLevel.L1);
        DefaultCacheChain chain = new DefaultCacheChain(List.of(l1));

        CountDownLatch loaderEnteredA = new CountDownLatch(1);
        CountDownLatch loaderProceedA = new CountDownLatch(1);
        AtomicInteger invocationsB = new AtomicInteger();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // Submit key-A get in background — its loader will block
            Future<String> futureA = executor.submit(() -> {
                return chain.get("key-A", STRING_TYPE, () -> {
                    loaderEnteredA.countDown();
                    try {
                        loaderProceedA.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return "value-A";
                }, nonNullablePolicy()).orElse(null);
            });

            // Wait until key-A's loader has been entered
            assertThat(loaderEnteredA.await(5, TimeUnit.SECONDS)).isTrue();

            // key-B get should complete immediately without waiting for key-A
            String resultB = chain.get("key-B", STRING_TYPE, () -> {
                invocationsB.incrementAndGet();
                return "value-B";
            }, nonNullablePolicy()).orElse(null);

            assertThat(resultB).isEqualTo("value-B");
            assertThat(invocationsB.get()).isEqualTo(1);

            // Release key-A and verify it completes
            loaderProceedA.countDown();
            assertThat(futureA.get(5, TimeUnit.SECONDS)).isEqualTo("value-A");
        } finally {
            executor.shutdownNow();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. Single-flight does not affect cache hit path
    // ──────────────────────────────────────────────────────────────────

    @Test
    void singleFlightDoesNotAffectCacheHitPath() {
        ConcurrentTestCache l1 = new ConcurrentTestCache("l1", CacheLevel.L1);
        l1.seed("cached-key", "cached-value");
        DefaultCacheChain chain = new DefaultCacheChain(List.of(l1));

        AtomicInteger loaderInvocations = new AtomicInteger();

        Optional<String> result = chain.get("cached-key", STRING_TYPE,
            () -> {
                loaderInvocations.incrementAndGet();
                return "should-not-be-called";
            },
            nonNullablePolicy());

        assertThat(result).contains("cached-value");
        assertThat(loaderInvocations.get()).isZero();
    }
}
