package com.holo.framework.horm.core;

import java.util.List;

/**
 * Minimal repository contract for CRUD operations on an entity type.
 *
 * <p>M1-6 only <em>declares</em> this interface so that {@link Model} and
 * {@link Horm} can compile. The JDBC-backed implementation
 * ({@code JdbcRepository}) is delivered in M1-7 and will translate these
 * calls into parameterized SQL driven by the {@link com.holo.framework.horm.meta.EntityMeta}
 * registered in {@link EntityMetaRegistry}.
 *
 * @param <T> entity type
 */
public interface Repository<T> {

    /** Fetches an entity by primary key, or {@code null} if not found. */
    T find(Object id);

    /** Fetches all entities of this type. */
    List<T> all();

    /** Returns the total number of persisted entities of this type. */
    long count();

    /** Returns {@code true} if an entity with the given id exists. */
    boolean exists(Object id);

    /**
     * Inserts or updates the entity. Implementations must backfill generated
     * primary keys onto {@code entity} after INSERT.
     */
    T save(T entity);

    /** Deletes the given entity by its primary key. */
    void delete(T entity);

    /** Deletes the entity with the given primary key. */
    void deleteById(Object id);
}
