package com.holo.framework.horm.meta.query;

import com.holo.framework.horm.meta.RelationType;

/**
 * Type-safe relation reference for the HORM query DSL.
 *
 * <p>One {@code RelationField} constant is generated per relation field on
 * the {@code XxxQueryMeta} class at compile time:
 * <pre>{@code
 * public static final RelationField<User, Order> ORDERS =
 *     RelationField.of(User.class, Order.class, "orders",
 *         RelationType.HAS_MANY, "user_id", null, null, null);
 * }</pre>
 *
 * <p>Used as the argument to {@code Query.fetch} / {@code Query.leftJoin} /
 * {@code Query.innerJoin} / {@code Query.join} so that the query builder
 * can resolve the target {@code EntityMeta} and render the JOIN clause
 * without reflection.
 *
 * <p>Standalone interface — does not implement {@link TypedField} because a
 * relation has no single scalar column that participates in WHERE clauses.
 *
 * @param <E> owning entity type
 * @param <T> target entity type
 *
 * @see TypedField
 * @see com.holo.framework.horm.meta.RelationMeta
 */
public interface RelationField<E, T> {

    /** The entity type that owns this relation. */
    Class<E> entityType();

    /** The target entity type this relation points to. */
    Class<T> targetType();

    /** Java property name of the relation field on the owning entity. */
    String name();

    /** Cardinality of the relation. */
    RelationType relationType();

    /** Foreign-key column. Semantics depend on {@link #relationType()}. May be {@code null}. */
    String foreignKey();

    /** Association foreign-key column (used by HABTM and HAS_MANY_THROUGH). May be {@code null}. */
    String associationForeignKey();

    /** Join table name (used by HABTM). May be {@code null}. */
    String joinTable();

    /** Through entity (used by HAS_MANY_THROUGH). May be {@code null}. */
    Class<?> through();

    /**
     * Build a {@code RelationField} constant.
     *
     * @param owner     owning entity type
     * @param target    target entity type
     * @param name      relation field name
     * @param type      relation cardinality
     * @param fk        foreign-key column (may be {@code null})
     * @param afk       association foreign-key column (may be {@code null})
     * @param jt        join table name (may be {@code null})
     * @param through   through entity class (may be {@code null})
     */
    static <E, T> RelationField<E, T> of(Class<E> owner,
                                         Class<T> target,
                                         String name,
                                         RelationType type,
                                         String fk,
                                         String afk,
                                         String jt,
                                         Class<?> through) {
        return new RelationField<E, T>() {
            @Override public Class<E> entityType() { return owner; }
            @Override public Class<T> targetType() { return target; }
            @Override public String name() { return name; }
            @Override public RelationType relationType() { return type; }
            @Override public String foreignKey() { return fk; }
            @Override public String associationForeignKey() { return afk; }
            @Override public String joinTable() { return jt; }
            @Override public Class<?> through() { return through; }

            @Override
            public String toString() {
                return "RelationField{" + owner.getSimpleName()
                    + "." + name + " -> " + target.getSimpleName()
                    + " (" + type + ")}";
            }
        };
    }
}
