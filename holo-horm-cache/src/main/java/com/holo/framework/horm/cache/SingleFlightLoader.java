package com.holo.framework.horm.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Coalesces concurrent load requests for the same key onto a single
 * loader invocation, preventing cache stampede (a.k.a. thundering herd)
 * when many threads request the same absent key simultaneously.
 *
 * <p>When N threads call {@link #load(Object, Supplier)} (or
 * {@link #loadAsync(Object, Supplier)}) with the same key at the same
 * time, only one thread executes the supplied {@code loader}; the
 * remaining N-1 threads block on the in-flight future and share its
 * result (or failure). Once the loader completes (successfully or not),
 * the in-flight entry is removed, so the next {@code load} call for the
 * same key will re-invoke the loader — this is single-flight, not a
 * cache.
 *
 * <p><b>Thread safety.</b> The in-flight map is a
 * {@link ConcurrentHashMap}, and entry creation is atomic via
 * {@link ConcurrentMap#computeIfAbsent}, so the single-flight invariant
 * holds under arbitrary concurrency.
 *
 * <p><b>Timeout.</b> The synchronous {@link #load} variant waits up to
 * a configurable timeout (default 30 seconds) for the in-flight future
 * to complete. A timeout is surfaced as a {@link CacheLoadException}
 * wrapping the {@link TimeoutException}; the in-flight future is
 * <em>not</em> cancelled, so other waiters may still observe the
 * eventual result. Note that the thread that wins the race and executes
 * the loader cannot time out — it must return from the loader for any
 * waiter to make progress.
 *
 * <p><b>Failure propagation.</b> A loader that throws propagates the
 * failure to every waiter as a {@link CacheLoadException} wrapping the
 * original exception (delivered via
 * {@link CompletableFuture#completeExceptionally(Throwable)}). The
 * original cause is preserved so callers can react to the specific
 * failure (e.g. a JDBC {@code SQLException}).
 *
 * <p><b>Resource lifecycle.</b> Implements {@link AutoCloseable} so
 * callers can clear the in-flight map via try-with-resources. Closing
 * does <em>not</em> cancel any outstanding futures; they complete
 * normally on their own and the map reference is dropped. After
 * {@code close()} returns, the loader is safe to reuse.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class SingleFlightLoader<K, V> implements AutoCloseable {

    /** Default wait timeout for {@link #load(Object, Supplier)}. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final ConcurrentMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    private final Duration timeout;

    /**
     * Creates a new loader with the default 30-second wait timeout.
     */
    public SingleFlightLoader() {
        this(DEFAULT_TIMEOUT);
    }

    /**
     * Creates a new loader with the supplied wait timeout.
     *
     * @param timeout the maximum time to wait for an in-flight load to
     *                complete in {@link #load}; must not be {@code null}
     *                and must be strictly positive
     */
    public SingleFlightLoader(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException(
                "timeout must be positive: " + timeout);
        }
        this.timeout = timeout;
    }

    /**
     * Synchronously loads the value for {@code key}, coalescing with any
     * concurrent load for the same key. Blocks until the loader completes
     * or the configured timeout elapses.
     *
     * <p>If this caller is the first to register an in-flight load for
     * {@code key}, the loader is invoked in the calling thread before
     * this method returns; the timeout does not apply to the loader
     * itself, only to waiters that join an in-flight future.
     *
     * @param key    the cache key; must not be {@code null}
     * @param loader the value supplier invoked only if no in-flight load
     *               exists for {@code key}; must not be {@code null}
     * @return the loaded value (may be {@code null} if the loader
     *         returns {@code null})
     * @throws CacheLoadException if the loader throws, the wait times
     *         out, or the calling thread is interrupted while waiting
     */
    public V load(K key, Supplier<V> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        CompletableFuture<V> future = loadAsync(key, loader);
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new CacheLoadException(
                "Single-flight load timed out for key " + key
                    + " after " + timeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CacheLoadException(
                "Single-flight load interrupted for key " + key, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CacheLoadException) {
                throw (CacheLoadException) cause;
            }
            throw new CacheLoadException(
                "Single-flight load failed for key " + key,
                cause != null ? cause : e);
        }
    }

    /**
     * Asynchronously loads the value for {@code key}, coalescing with
     * any concurrent load for the same key. The returned future
     * completes when the loader finishes (or is already complete if a
     * concurrent caller has already resolved it).
     *
     * <p>If this call is the first to register an in-flight entry for
     * {@code key}, the loader is invoked synchronously in the calling
     * thread before this method returns, and the returned future is
     * already completed. Subsequent callers receive the same future
     * without invoking the loader.
     *
     * <p>The returned future completes exceptionally with a
     * {@link CacheLoadException} (wrapping the original loader failure)
     * if the loader throws. Callers that join via
     * {@link CompletableFuture#join()} will observe a
     * {@link java.util.concurrent.CompletionException} wrapping that
     * {@link CacheLoadException}.
     *
     * @param key    the cache key; must not be {@code null}
     * @param loader the value supplier invoked only if no in-flight load
     *               exists for {@code key}; must not be {@code null}
     * @return a non-null future that completes with the loaded value or
     *         completes exceptionally with a {@link CacheLoadException}
     */
    public CompletableFuture<V> loadAsync(K key, Supplier<V> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        CompletableFuture<V> candidate = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.computeIfAbsent(key, k -> candidate);
        if (existing != candidate) {
            // Another caller already registered an in-flight future.
            return existing;
        }
        // We won the race: invoke the loader in this thread.
        try {
            V value = loader.get();
            candidate.complete(value);
        } catch (Throwable t) {
            candidate.completeExceptionally(wrap(t, key));
        } finally {
            inFlight.remove(key, candidate);
        }
        return candidate;
    }

    /**
     * Returns the number of currently in-flight loads. Primarily useful
     * for diagnostics, monitoring and tests. The count is a snapshot and
     * may change immediately after this method returns.
     *
     * @return the count of keys with an outstanding load
     */
    public int inFlightCount() {
        return inFlight.size();
    }

    /**
     * Clears the in-flight map. Does not cancel any outstanding futures;
     * they complete normally on their own and the map reference is
     * dropped. Idempotent.
     */
    @Override
    public void close() {
        inFlight.clear();
    }

    private static RuntimeException wrap(Throwable t, Object key) {
        if (t instanceof CacheLoadException) {
            return (CacheLoadException) t;
        }
        return new CacheLoadException(
            "Single-flight load failed for key " + key, t);
    }
}
