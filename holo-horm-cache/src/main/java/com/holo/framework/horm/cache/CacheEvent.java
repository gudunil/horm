package com.holo.framework.horm.cache;

import java.time.Instant;
import java.util.Objects;

/**
 * An observation emitted by a {@link Cache} to registered
 * {@link CacheEventListener}s.
 *
 * <p>Events are emitted as a side effect of cache operations (get, put,
 * invalidate, etc.) and are purely informational: listeners must not
 * throw, block or otherwise disrupt the cache thread. Implementations
 * that need heavyweight processing should hand off to a queue.
 *
 * <p>The record is intentionally permissive on key/value types
 * ({@code Object}): the same listener may be registered against caches
 * of different generic signatures, and pinning the type would force
 * unsafe casts at registration sites. Listeners that care about types
 * should narrow with {@code instanceof} rather than rely on generics.
 *
 * <p>{@code error} is non-null only when {@link #type()} is
 * {@link CacheEventType#ERROR}; for other types it must be {@code null}.
 * {@code key} and {@code value} may be {@code null} (e.g. for
 * bulk-invalidate events that do not target a specific key).
 *
 * @param type       the event kind; never {@code null}
 * @param cacheName  the {@link Cache#name()} that emitted the event; never {@code null}
 * @param level      the {@link Cache#level()} that emitted the event; never {@code null}
 * @param key        the affected key, or {@code null} for events without a key
 * @param value      the affected value, or {@code null} for events without a value
 * @param timestamp  the moment the event was raised; never {@code null}
 * @param error      the failure cause for {@link CacheEventType#ERROR} events,
 *                   otherwise {@code null}
 */
public record CacheEvent(CacheEventType type,
                         String cacheName,
                         CacheLevel level,
                         Object key,
                         Object value,
                         Instant timestamp,
                         Throwable error) {

    /**
     * Compact canonical constructor: validates the non-null invariants
     * documented above. The canonical record validation cannot express
     * "non-null unless ERROR", so we enforce the documented contract here
     * and let the listener side stay simple.
     */
    public CacheEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(cacheName, "cacheName");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(timestamp, "timestamp");
        if (error != null && type != CacheEventType.ERROR) {
            throw new IllegalArgumentException(
                "error must be null for event type " + type);
        }
        if (error == null && type == CacheEventType.ERROR) {
            throw new IllegalArgumentException(
                "error must be non-null for event type ERROR");
        }
    }

    /**
     * Convenience factory for the common case of an event with no error.
     * Equivalent to {@code new CacheEvent(type, cacheName, level, key,
     * value, Instant.now(), null)}.
     */
    public static CacheEvent of(CacheEventType type,
                                String cacheName,
                                CacheLevel level,
                                Object key,
                                Object value) {
        return new CacheEvent(type, cacheName, level, key, value, Instant.now(), null);
    }

    /**
     * Convenience factory for an {@link CacheEventType#ERROR} event.
     * The timestamp defaults to {@link Instant#now()}.
     */
    public static CacheEvent error(String cacheName,
                                   CacheLevel level,
                                   Object key,
                                   Throwable error) {
        Objects.requireNonNull(error, "error");
        return new CacheEvent(CacheEventType.ERROR, cacheName, level, key,
            null, Instant.now(), error);
    }
}
