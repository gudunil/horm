package com.holo.framework.horm.core;

/**
 * Standalone entity class referenced by {@link IndexedEntityMeta}.
 *
 * <p>Deliberately <em>not</em> annotated with {@code @Entity}: the registry
 * only needs a {@link Class} to key on, and we want to drive
 * {@link EntityMetaRegistry#loadIndex()} without invoking the APT. The
 * matching {@link IndexedEntityMeta} is hand-written.
 */
public final class IndexedEntity {
    // No state needed; this class exists only to be a registry key.
}
