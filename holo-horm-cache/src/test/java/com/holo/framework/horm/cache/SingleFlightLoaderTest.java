package com.holo.framework.horm.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SingleFlightLoader}.
 *
 * <p>Coverage focus:
 * <ul>
 *   <li>single-thread happy path: {@code load} returns the loader's value;</li>
 *   <li>single-flight semantics: 100 concurrent requests for the same key
 *       invoke the loader exactly once and all observe the same value;</li>
 *   <li>failure propagation: a loader that throws delivers the same
 *       cause to every waiter;</li>
 *   <li>timeout: a waiter whose {@code load} call exceeds the configured
 *       timeout raises a {@link CacheLoadException} wrapping a
 *       {@link TimeoutException};</li>
 *   <li>key isolation: concurrent loads for different keys do not block
 *       each other;</li>
 *   <li>post-completion re-entry: a {@code load} for a key whose prior
 *       load has completed invokes the loader again (single-flight, not
 *       a cache).</li>
 * </ul>
 */
class SingleFlightLoaderTest {

    // ── single-thread happy path ───────────────────────────────────────

    @Test
    void loadReturnsLoaderValue() {
        SingleFlightLoader<String, String> sf = new SingleFlightLoader<>();
        AtomicInteger invocations = new AtomicInteger();

        String result = sf.load("k", () -> {
            invocations.incrementAndGet();
            return "v";
        });

        assertThat(result).isEqualTo("v");
        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    void loadCanReturnNullWhenLoaderReturnsNull() {
        SingleFlightLoader<String, String> sf = new SingleFlightLoader<>();

        String result = sf.load("k", () -> null);

        assertThat(result).isNull();
    }

    @Test
    void loadSameKeyTwiceInvokesLoaderTwiceAfterFirstCompletes() {
        // SingleFlightLoader is single-flight, not a cache: once the
        // in-flight entry is removed, the next call re-enters the loader.
        SingleFlightLoader<String, String> sf = new SingleFlightLoader<>();
        AtomicInteger invocations = new AtomicInteger();

        String first = sf.load("k", () -> {
            invocations.incrementAndGet();
            return "v1";
        });
        String second = sf.load("k", () -> {
            invocations.incrementAndGet();
            return "v2";
        });

        assertThat(first).isEqualTo("v1");
        assertThat(second).isEqualTo("v2");
        assertThat(invocations.get()).isEqualTo(2);
    }

    @Test
    void loadRejectsNullKey() {
        SingleFlightLoader<String, String> sf = new SingleFlightLoader<>();
        assertThatThrownBy(() -> sf.load(null, () -> "v"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("key");
    }

    @Test
    void loadRejectsNullLoader() {
        SingleFlightLoader<String, String> sf = new SingleFlightLoader<>();
        assertThatThrownBy(() -> sf.load("k", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("loader");
    }

    @Test
    void constructorRejectsNullTimeout() {
        assertThatThrownBy(() -> new SingleFlightLoader<String, String>((Duration) null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("timeout");
    }

    @Test
    void constructorRejectsZeroOrNegativeTimeout() {
        assertThatThrownBy(() -> new SingleFlightLoader<String, String>(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SingleFlightLoader<String, String>(Duration.ofMillis(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── concurrent single-flight coalescing ────────────────────────────

    @Test
    @Timeout(30)
    void concurrentLoadsForSameKeyInvokeLoaderOnce() throws Exception {
        SingleFlightLoader<String, String> sf = new SingleFlightLoader<>();
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        // Synchronize all threads at the sf.load() call site so they all
        // enter load() within the same scheduling quantum. Without this
        // barrier, some threads may not reach sf.load() until after the
        // loader has completed and the in-flight entry has been removed,
        // causing them to re-invoke the loader and break the
        // invocations==1 invariant.
        CyclicBarrier allAtLoadCall = new CyclicBarrier(threadCount);
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch loaderProceed = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        // Counts threads that have passed the CyclicBarrier and are about
        // to enter sf.load(). The main thread waits for this to reach
        // threadCount before releasing the loader, so every thread has
        // already been scheduled past the barrier and will reach
        // computeIfAbsent within microseconds — long before the winner's
        // in-flight entry can be removed.
        AtomicInteger passedBarrier = new AtomicInteger();

        try {
            List<Future<String>> futures = new ArrayList<>(threadCount);
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    allAtLoadCall.await();
                    passedBarrier.incrementAndGet();
                    return sf.load("same-key", () -> {
                        invocations.incrementAndGet();
                        loaderEntered.countDown();
                        try {
                            loaderProceed.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return "value";
                    });
                }));
            }

            ready.await();
            start.countDown();

            assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            // Wait until every thread has passed the CyclicBarrier and is
            // about to enter sf.load() (i.e. has been scheduled past the
            // barrier). This is far more reliable than a fixed sleep,
            // because it directly observes scheduler progress rather than
            // guessing a duration. Once passedBarrier == threadCount, the
            // only instructions between each waiter and computeIfAbsent are
            // the null-checks inside load()/loadAsync(), which complete in
            // microseconds.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (passedBarrier.get() < threadCount) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError(
                        "Timed out waiting for threads to pass barrier: "
                            + passedBarrier.get() + "/" + threadCount);
                }
                Thread.sleep(5);
            }
            // Tiny grace period so the last few threads can execute the
            // handful of instructions between incrementAndGet() and
            // computeIfAbsent().
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

    @Test
    @Timeout(30)
    void concurrentLoadsForSameKeyAllObserveSameValue() throws Exception {
        // Variant of the above without the loader-side latches: a single
        // quick loader that returns a unique object reference. All 100
        // callers must observe exactly that reference.
        SingleFlightLoader<String, String> sf = new SingleFlightLoader<>();
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(threadCount);

        try {
            String shared = "shared-instance-" + System.nanoTime();
            List<Future<String>> futures = new ArrayList<>(threadCount);
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return sf.load("k", () -> shared);
                }));
            }

            ready.await();
            start.countDown();

            for (Future<String> f : futures) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isSameAs(shared);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // ── failure propagation ────────────────────────────────────────────

    @Test
    @Timeout(30)
    void loaderFailureIsPropagatedToAllWaitersAsCacheLoadException() throws Exception {
        SingleFlightLoader<String, String> sf = new SingleFlightLoader<>();
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CyclicBarrier allAtLoadCall = new CyclicBarrier(threadCount);
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch loaderProceed = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        // See concurrentLoadsForSameKeyInvokeLoaderOnce for why this is
        // necessary: we must observe that every thread has been scheduled
        // past the barrier (and is about to enter sf.load()) before
        // releasing the loader, otherwise late-arriving waiters miss the
        // in-flight entry and re-invoke the loader.
        AtomicInteger passedBarrier = new AtomicInteger();

        IllegalStateException sharedCause = new IllegalStateException("boom");

        try {
            List<Future<String>> futures = new ArrayList<>(threadCount);
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    allAtLoadCall.await();
                    passedBarrier.incrementAndGet();
                    return sf.load("k", () -> {
                        invocations.incrementAndGet();
                        loaderEntered.countDown();
                        try {
                            loaderProceed.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        throw sharedCause;
                    });
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
                // f.get() throws ExecutionException(cause=CacheLoadException(cause=IllegalStateException("boom")))
                assertThatThrownBy(() -> f.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(CacheLoadException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("boom");
            }
            assertThat(invocations.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void loadAsyncFailureIsDeliveredAsCompletionExceptionOnJoin() {
        SingleFlightLoader<String, String> sf = new SingleFlightLoader<>();

        CompletableFuture<String> future = sf.loadAsync("k", () -> {
            throw new IllegalStateException("loader-failed");
        });

        assertThatThrownBy(future::join)
            .isInstanceOf(CompletionException.class)
            .hasCauseInstanceOf(CacheLoadException.class);
        assertThat(future).isCompletedExceptionally();
    }

    @Test
    void loaderThrowsCacheLoadExceptionDirectlyIsNotDoubleWrapped() {
        SingleFlightLoader<String, String> sf = new SingleFlightLoader<>();
        CacheLoadException original = new CacheLoadException("direct", new RuntimeException("root"));

        assertThatThrownBy(() -> sf.load("k", () -> {
            throw original;
        })).isSameAs(original);
    }

    // ── timeout ────────────────────────────────────────────────────────

    @Test
    @Timeout(30)
    void loadTimesOutWhenInFlightLoadExceedsTimeout() throws Exception {
        SingleFlightLoader<String, String> sf =
            new SingleFlightLoader<>(Duration.ofMillis(100));
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch loaderProceed = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // Background thread wins the race and enters the loader body,
            // then blocks on loaderProceed so the in-flight future stays
            // unresolved for the duration of the test.
            Future<?> background = executor.submit(() -> {
                try {
                    return sf.load("slow-key", () -> {
                        loaderEntered.countDown();
                        try {
                            loaderProceed.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return "value";
                    });
                } catch (Throwable t) {
                    return t;
                }
            });

            // Wait for the background thread to register the in-flight load.
            assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // The test thread should time out waiting for the in-flight
            // future. The fallback loader below must never be invoked
            // because the in-flight entry is still registered.
            AtomicBoolean fallbackInvoked = new AtomicBoolean();
            assertThatThrownBy(() -> sf.load("slow-key", () -> {
                fallbackInvoked.set(true);
                return "should-not-be-invoked";
            }))
                .isInstanceOf(CacheLoadException.class)
                .hasMessageContaining("timed out")
                .hasCauseInstanceOf(TimeoutException.class);
            assertThat(fallbackInvoked).isFalse();

            // Release the background thread so it can complete and the
            // loader body returns normally; the in-flight entry is then
            // removed and the loader is safe to reuse.
            loaderProceed.countDown();
            Object backgroundResult = background.get(10, TimeUnit.SECONDS);
            assertThat(backgroundResult).isEqualTo("value");
        } finally {
            executor.shutdownNow();
        }
    }

    // ── key isolation ──────────────────────────────────────────────────

    @Test
    @Timeout(30)
    void differentKeysDoNotBlockEachOther() throws Exception {
        SingleFlightLoader<String, String> sf = new SingleFlightLoader<>();
        int keyCount = 20;
        int threadsPerKey = 10;
        int totalThreads = keyCount * threadsPerKey;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(totalThreads);
        // Synchronize all threads at the sf.load() call site so they all
        // enter load() within the same scheduling quantum. Without this
        // barrier, some threads may not reach sf.load() until after their
        // key's loader has completed and the in-flight entry has been
        // removed, causing them to re-invoke the loader and inflate
        // totalInvocations beyond keyCount.
        CyclicBarrier allAtLoadCall = new CyclicBarrier(totalThreads);
        // Each key's loader signals it has been entered; the test waits
        // for all keyCount loaders to be in-flight before releasing them.
        CountDownLatch loaderEntered = new CountDownLatch(keyCount);
        CountDownLatch loaderProceed = new CountDownLatch(1);
        AtomicInteger totalInvocations = new AtomicInteger();
        // See concurrentLoadsForSameKeyInvokeLoaderOnce for why this is
        // necessary: we must observe that every thread has been scheduled
        // past the barrier before releasing the loaders, otherwise
        // late-arriving waiters miss the in-flight entry and re-invoke
        // the loader.
        AtomicInteger passedBarrier = new AtomicInteger();

        try {
            List<Future<String>> futures = new ArrayList<>(totalThreads);
            for (int k = 0; k < keyCount; k++) {
                final String key = "key-" + k;
                final String value = "value-" + k;
                for (int t = 0; t < threadsPerKey; t++) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        allAtLoadCall.await();
                        passedBarrier.incrementAndGet();
                        return sf.load(key, () -> {
                            totalInvocations.incrementAndGet();
                            loaderEntered.countDown();
                            try {
                                loaderProceed.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                            return value;
                        });
                    }));
                }
            }

            ready.await();
            start.countDown();
            // Wait until one loader per key has been entered (keyCount
            // in-flight entries), then release them all.
            assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            // Wait until every thread has passed the CyclicBarrier and is
            // about to enter sf.load() — see the analogous pattern in
            // concurrentLoadsForSameKeyInvokeLoaderOnce.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (passedBarrier.get() < totalThreads) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError(
                        "Timed out waiting for threads to pass barrier: "
                            + passedBarrier.get() + "/" + totalThreads);
                }
                Thread.sleep(5);
            }
            Thread.sleep(20);
            loaderProceed.countDown();

            for (int k = 0; k < keyCount; k++) {
                String expected = "value-" + k;
                for (int t = 0; t < threadsPerKey; t++) {
                    int idx = k * threadsPerKey + t;
                    assertThat(futures.get(idx).get(10, TimeUnit.SECONDS)).isEqualTo(expected);
                }
            }
            // Each key's loader is invoked exactly once.
            assertThat(totalInvocations.get()).isEqualTo(keyCount);
        } finally {
            executor.shutdownNow();
        }
    }

    // ── loadAsync basics ───────────────────────────────────────────────

    @Test
    void loadAsyncReturnsCompletedFutureForFirstCaller() {
        SingleFlightLoader<String, String> sf = new SingleFlightLoader<>();

        CompletableFuture<String> future = sf.loadAsync("k", () -> "v");

        assertThat(future).isCompleted();
        assertThat(future.getNow(null)).isEqualTo("v");
    }

    @Test
    void loadAsyncReturnsCompletedFutureWithNullValue() {
        SingleFlightLoader<String, String> sf = new SingleFlightLoader<>();

        CompletableFuture<String> future = sf.loadAsync("k", () -> null);

        assertThat(future).isCompleted();
        assertThat(future.getNow("fallback")).isNull();
    }

    @Test
    void loadDelegatesToLoadAsync() {
        // Sanity check: load() returns the same value that loadAsync()
        // would complete with.
        SingleFlightLoader<String, Integer> sf = new SingleFlightLoader<>();
        Integer result = sf.load("k", () -> 42);
        assertThat(result).isEqualTo(42);
    }

    // ── close / lifecycle ──────────────────────────────────────────────

    @Test
    void closeClearsInFlightMap() {
        SingleFlightLoader<String, String> sf = new SingleFlightLoader<>();
        assertThat(sf.inFlightCount()).isZero();

        // A completed load leaves no in-flight entry.
        sf.load("k", () -> "v");
        assertThat(sf.inFlightCount()).isZero();

        // close() is idempotent and safe to call on an empty map.
        sf.close();
        assertThat(sf.inFlightCount()).isZero();
    }

    @Test
    void loaderIsReusableAfterClose() {
        SingleFlightLoader<String, String> sf = new SingleFlightLoader<>();
        sf.load("k", () -> "v1");
        sf.close();

        AtomicInteger invocations = new AtomicInteger();
        String result = sf.load("k", () -> {
            invocations.incrementAndGet();
            return "v2";
        });
        assertThat(result).isEqualTo("v2");
        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    void defaultTimeoutIsThirtySeconds() {
        assertThat(SingleFlightLoader.DEFAULT_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
    }
}
