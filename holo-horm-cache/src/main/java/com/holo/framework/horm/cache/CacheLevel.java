package com.holo.framework.horm.cache;

/**
 * Identifies the position of a {@link Cache} within a multi-level
 * {@link CacheChain}.
 *
 * <p>The hierarchy follows the conventional three-tier cache topology:
 * <ul>
 *   <li>{@link #L1} — in-process local cache (e.g. Caffeine). Lowest latency,
 *       but not shared across JVM instances.</li>
 *   <li>{@link #L2} — distributed in-memory cache (e.g. Redis). Shared across
 *       instances with sub-millisecond latency.</li>
 *   <li>{@link #L3} — remote persistent store. The authoritative data source
 *       (typically the database); highest latency, used as the cache-miss
 *       backstop rather than as a true cache.</li>
 * </ul>
 *
 * <p>Levels are ordered: a lookup traverses L1 → L2 → L3 and back-fills
 * upper layers on hit. {@link CacheChain#insertAfter} relies on the
 * declaration order here to position new caches.
 */
public enum CacheLevel {

    L1,
    L2,
    L3
}
