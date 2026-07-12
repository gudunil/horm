package com.holo.framework.horm.meta.annotation;

/**
 * Identifies the position of a cache within a multi-level cache chain.
 *
 * <p>Declared on a {@link Cached} entity to select which cache levels should
 * participate for that entity. The runtime cache chain (in
 * {@code holo-horm-cache}) mirrors these levels; the ORM integration layer
 * translates the compile-time {@code CacheLevel} constants into the
 * corresponding runtime cache configuration.
 *
 * <ul>
 *   <li>{@link #L1} — in-process local cache (e.g. Caffeine).</li>
 *   <li>{@link #L2} — distributed in-memory cache (e.g. Redis).</li>
 *   <li>{@link #L3} — remote persistent store (the database itself).</li>
 * </ul>
 *
 * <p>This enum lives in the meta module so that the {@code @Cached} annotation
 * can reference it without forcing the meta module to depend on the cache
 * module. The cache module defines its own
 * {@code com.holo.framework.horm.cache.CacheLevel} with identical constant
 * names; the ORM integration layer converts between the two via
 * {@code name()}.
 */
public enum CacheLevel {

    L1,
    L2,
    L3
}
