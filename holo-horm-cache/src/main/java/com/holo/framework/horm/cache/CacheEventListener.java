package com.holo.framework.horm.cache;

/**
 * Observer of {@link CacheEvent}s emitted by a {@link Cache}.
 *
 * <p>Listeners are registered with a cache (or a {@link CacheChain}) at
 * configuration time and receive every event the cache raises. The
 * contract is deliberately minimal — a single {@link #onEvent} method —
 * so that listeners can be expressed as lambdas:
 *
 * <pre>{@code
 * cache.addListener(event -> {
 *     if (event.type() == CacheEventType.MISS) {
 *         missCounter.increment();
 *     }
 * });
 * }</pre>
 *
 * <p><b>Threading contract.</b> Implementations MUST be non-blocking and
 * thread-safe. The cache implementation is free to dispatch events on
 * its own thread (the calling thread of the triggering operation, an
 * async executor, or a dedicated event dispatcher); listeners that need
 * to mutate shared state must do so under proper synchronisation.
 *
 * <p><b>Failure isolation.</b> A listener that throws will not abort the
 * triggering cache operation; cache implementations are expected to catch
 * and log listener exceptions (typically via SLF4J) rather than propagate
 * them. Listeners that want richer error handling should catch internally.
 */
@FunctionalInterface
public interface CacheEventListener {

    /**
     * Invoked when the cache raises an event.
     *
     * @param event the event; never {@code null}
     */
    void onEvent(CacheEvent event);
}
