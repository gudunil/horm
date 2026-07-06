package com.holo.framework.horm.meta.annotation;

/**
 * Eviction strategy for a bounded cache, declared on a {@link CachePolicy}
 * annotation.
 *
 * <ul>
 *   <li>{@link #LRU} — Least Recently Used.</li>
 *   <li>{@link #LFU} — Least Frequently Used.</li>
 *   <li>{@link #W_TINY_LFU} — Windowed TinyLFU (Caffeine default).</li>
 *   <li>{@link #TTL} — Time-To-Live only (no size bound).</li>
 * </ul>
 *
 * <p>This enum lives in the meta module so that the {@code @CachePolicy}
 * annotation can reference it without forcing the meta module to depend on
 * the cache module. The cache module defines its own
 * {@code com.holo.framework.horm.cache.EvictionPolicy} with identical
 * constant names; the ORM integration layer converts between the two via
 * {@code name()}.
 */
public enum EvictionPolicy {

    LRU,
    LFU,
    W_TINY_LFU,
    TTL
}
