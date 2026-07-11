package com.holo.framework.horm.meta;

/**
 * Cardinality of an entity relationship.
 *
 * <p>Defined in M1 as part of the metadata contract so that
 * {@link RelationMeta} can reference it; full loading behavior ships in M3.
 *
 * @see RelationMeta
 */
public enum RelationType {

    /** Child belongs to parent (e.g. Order belongs to User). Foreign key on the child. */
    BELONGS_TO,

    /** Parent has exactly one child (e.g. User has one Profile). Foreign key on the child. */
    HAS_ONE,

    /** Parent has many children (e.g. User has many Orders). Foreign key on the child. */
    HAS_MANY,

    /** Many-to-many via a join table (e.g. User ↔ Role via user_roles). */
    HAS_AND_BELONGS_TO_MANY,

    /** Has-many through a join entity (e.g. User has many Products through Order). */
    HAS_MANY_THROUGH
}
