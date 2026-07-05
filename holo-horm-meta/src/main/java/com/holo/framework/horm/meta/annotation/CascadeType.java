package com.holo.framework.horm.meta.annotation;

/**
 * Defines the set of cascade types for relation annotations.
 *
 * <p>When specified on a relation annotation (e.g.
 * {@code @HasMany(cascade = {CascadeType.PERSIST, CascadeType.REMOVE})}),
 * the listed operations are propagated from the parent entity to the
 * related child entities.
 *
 * @see BelongsTo
 * @see HasOne
 * @see HasMany
 * @see HasAndBelongsToMany
 * @see HasManyThrough
 */
public enum CascadeType {

    /** Cascade persist (save) operation. */
    PERSIST,

    /** Cascade merge (update) operation. */
    MERGE,

    /** Cascade remove (delete) operation. */
    REMOVE,

    /** Cascade detach operation. */
    DETACH,

    /** Cascade refresh operation. */
    REFRESH,

    /** Cascade all operations listed above. */
    ALL
}
