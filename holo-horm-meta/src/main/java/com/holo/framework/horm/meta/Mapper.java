package com.holo.framework.horm.meta;

import java.util.List;

/**
 * Zero-reflection bidirectional mapper between an entity and a {@link Row}.
 *
 * <p>The {@code holo-horm-meta} annotation processor generates one
 * {@code Mapper} implementation per {@code @Entity} (e.g. {@code UserMapper}).
 * The generated {@code map} / {@code toRow} methods call setters/getters
 * directly — no {@code Field.get} / {@code Field.set} reflection.
 *
 * <p>Field access via name uses {@code switch} on a string, which the Java
 * compiler lowers to {@code tableswitch} / {@code lookupswitch} for O(1)
 * dispatch.
 *
 * @param <T> entity type
 */
public interface Mapper<T> {

    /**
     * Materializes an entity from a {@link Row}. Implementations must tolerate
     * missing columns — {@code row.has(column)} should be checked before
     * calling typed accessors.
     */
    T map(Row row);

    /**
     * Serializes an entity to a {@link Row} for persistence. Implementations
     * must skip {@code null} id fields on INSERT so the data source can
     * generate the value.
     */
    Row toRow(T entity);

    /** Returns the primary key value of the entity. */
    Object getId(T entity);

    /** Sets the primary key value on the entity (used after INSERT to backfill generated keys). */
    void setId(T entity, Object id);

    /** Returns the value of a named field. Throws on unknown field. */
    Object getField(T entity, String field);

    /** Sets the value of a named field. Throws on unknown field. */
    void setField(T entity, String field, Object value);

    /**
     * Sets a relation collection on the entity by Java property name.
     *
     * <p>Default implementation throws {@link UnsupportedOperationException}
     * so that M1/M2 hand-written mocks (which do not exercise relations)
     * remain compatible without needing to implement this method. APT-generated
     * mappers override this with a {@code switch(name)} dispatch to the
     * entity's typed setter (e.g. {@code u.setOrders((List<Order>) related)}).
     *
     * @param entity  the entity instance to mutate
     * @param name    relation property name (matches the field name)
     * @param related the related entity collection; never {@code null} but
     *                may be empty
     */
    default void setRelation(T entity, String name, List<?> related) {
        throw new UnsupportedOperationException("Relation not mapped: " + name);
    }
}
