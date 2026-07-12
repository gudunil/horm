package com.holo.framework.horm.meta.annotation;

/**
 * Write strategy governing how cache writes propagate to the authoritative
 * data source, declared on a {@link CachePolicy} annotation.
 *
 * <ul>
 *   <li>{@link #THROUGH} — Write-Through. Cache synchronously writes to the
 *       backing store before acknowledging the caller.</li>
 *   <li>{@link #BEHIND} — Write-Behind. Cache acknowledges immediately and
 *       persists asynchronously.</li>
 *   <li>{@link #AROUND} — Write-Around. Cache is bypassed on write and only
 *       populated on subsequent reads.</li>
 * </ul>
 *
 * <p>This enum lives in the meta module so that the {@code @CachePolicy}
 * annotation can reference it without forcing the meta module to depend on
 * the cache module. The cache module defines its own
 * {@code com.holo.framework.horm.cache.WriteStrategy} with identical
 * constant names; the ORM integration layer converts between the two via
 * {@code name()}.
 */
public enum WriteStrategy {

    THROUGH,
    BEHIND,
    AROUND
}
