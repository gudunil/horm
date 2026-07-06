package com.holo.framework.horm.cache;

/**
 * Type of an observation published via {@link CacheEvent} to registered
 * {@link CacheEventListener}s.
 *
 * <p>Events are emitted by the {@link Cache} implementation as a side
 * effect of normal operation; listeners must not block the cache thread.
 * The set is closed: implementers MUST NOT emit events of any other type,
 * so that listener dispatch can use a {@code switch} exhaustive check.
 *
 * <ul>
 *   <li>{@link #HIT} — A {@code get} found a non-null entry in this cache.</li>
 *   <li>{@link #MISS} — A {@code get} did not find the entry (absent or
 *       expired). Frequently paired with a subsequent {@link #LOAD}.</li>
 *   <li>{@link #EVICT} — An entry was removed by the eviction policy
 *       (size/weight pressure), not by explicit invalidation.</li>
 *   <li>{@link #EXPIRE} — An entry was removed because its TTL elapsed.</li>
 *   <li>{@link #INVALIDATE} — An entry was removed by an explicit
 *       {@link Cache#invalidate}/{@link Cache#invalidateAll} call.</li>
 *   <li>{@link #ERROR} — An operation failed (load exception, serialization
 *       error, etc.). The {@link CacheEvent#error()} field carries the cause.</li>
 * </ul>
 */
public enum CacheEventType {

    HIT,
    MISS,
    EVICT,
    EXPIRE,
    INVALIDATE,
    ERROR
}
