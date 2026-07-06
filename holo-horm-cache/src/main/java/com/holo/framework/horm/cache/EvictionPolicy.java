package com.holo.framework.horm.cache;

/**
 * Eviction strategy for a bounded {@link Cache}.
 *
 * <p>Selecting an eviction policy is a trade-off between memory footprint,
 * cache-hit rate and CPU cost. The policy is declared on a {@link CachePolicy}
 * and honoured by the underlying cache provider (Caffeine / Redisson / etc.).
 *
 * <ul>
 *   <li>{@link #LRU} — Least Recently Used. Cheap and good for recency-skewed
 *       access patterns.</li>
 *   <li>{@link #LFU} — Least Frequently Used. Better for stable popularity
 *       distributions, but pays bookkeeping overhead.</li>
 *   <li>{@link #W_TINY_LFU} — Windowed TinyLFU (Caffeine default). Combines
 *       recency and frequency sketches; near-optimal hit rate at modest
 *       memory cost.</li>
 *   <li>{@link #TTL} — Time-To-Live only. Entries are evicted on expiry
 *       without size bound; appropriate for caches that must reflect
 *       eventual-consistency windows.</li>
 * </ul>
 */
public enum EvictionPolicy {

    LRU,
    LFU,
    W_TINY_LFU,
    TTL
}
