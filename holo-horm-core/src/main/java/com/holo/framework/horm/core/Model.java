package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.Mapper;

import java.util.List;

/**
 * Active Record base class for HORM entities.
 *
 * <p>Subclasses follow the CRTP self-referential pattern so that
 * {@code self()} returns the concrete subtype:
 * <pre>{@code
 * @Entity(table = "users")
 * public class User extends Model<User> { ... }
 * }</pre>
 *
 * <p>All metadata access goes through {@link EntityMetaRegistry}, which is
 * populated at class-init from {@code META-INF/horm/entities.idx}. Once the
 * registry is warm, every method below is a zero-reflection O(1) lookup:
 * the {@link Mapper} invoked by {@link #idValue()} is the APT-generated
 * implementation that calls getters/setters directly.
 *
 * <p>M1-6 ships the method surface that does not depend on a JDBC
 * repository: {@link #isPersisted()} is fully usable as soon as a
 * {@link EntityMeta} is registered. {@link #save()}, {@link #delete()},
 * {@link #reload()} and the static {@code find}/{@code all}/{@code count}
 * helpers route through {@link Horm#repository(Class)}, which throws
 * {@link UnsupportedOperationException} until M1-7 wires in
 * {@code JdbcRepository}.
 *
 * @param <T> the concrete entity subtype
 */
public abstract class Model<T extends Model<T>> {

    @SuppressWarnings("unchecked")
    protected final T self() {
        return (T) this;
    }

    /**
     * Resolves the {@link EntityMeta} for the runtime class of this instance.
     *
     * <p>The CRTP contract ({@code T extends Model<T>}) guarantees that
     * {@code getClass()} at runtime is exactly {@code T}'s class, so the
     * unchecked cast is safe.
     */
    @SuppressWarnings("unchecked")
    private EntityMeta<T> meta() {
        return EntityMetaRegistry.lookup((Class<T>) (Class<?>) getClass());
    }

    /** Returns the APT-generated {@link Mapper} for this entity type. */
    protected final Mapper<T> mapper() {
        return meta().mapper();
    }

    /**
     * Returns the primary key value of this instance, or {@code null} if
     * the id has not been assigned yet.
     *
     * @throws IllegalStateException if the entity has no {@code @Id} field
     */
    protected final Object idValue() {
        EntityMeta<T> meta = meta();
        if (meta.idField() == null) {
            throw new IllegalStateException(
                "Entity " + getClass().getName() + " has no @Id field");
        }
        return meta.mapper().getId(self());
    }

    // ===== Instance methods =====

    /**
     * Inserts or updates this entity via the configured {@link Repository}.
     */
    public final void save() {
        repository().save(self());
    }

    /** Deletes this entity by its primary key. */
    public final void delete() {
        repository().delete(self());
    }

    /**
     * Returns {@code true} if this instance has a non-null primary key
     * (i.e. it is presumed to exist in the data source).
     */
    public final boolean isPersisted() {
        return idValue() != null;
    }

    /**
     * Re-reads this entity from the data source by primary key.
     *
     * <p>Effective once M1-7 ships {@code JdbcRepository}; in M1-6 the call
     * surfaces the {@link UnsupportedOperationException} thrown by
     * {@link Horm#repository(Class)}.
     */
    public final T reload() {
        // TODO M1-7: JdbcRepository.find drives this; M1-6 surfaces the stub.
        return repository().find(idValue());
    }

    // ===== Static query helpers =====

    /** Fetches an entity by primary key. */
    public static <T extends Model<T>> T find(Class<T> type, Object id) {
        return Horm.repository(type).find(id);
    }

    /** Fetches all entities of the given type. */
    public static <T extends Model<T>> List<T> all(Class<T> type) {
        return Horm.repository(type).all();
    }

    /** Returns the total number of persisted entities of the given type. */
    public static <T extends Model<T>> long count(Class<T> type) {
        return Horm.repository(type).count();
    }

    /**
     * Returns the {@link Repository} for this instance's runtime type.
     *
     * <p>Routing through {@link Horm} (rather than constructing a repository
     * directly) keeps a single seam for M1-7 to plug in {@code JdbcRepository}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Repository<T> repository() {
        return (Repository<T>) Horm.repository((Class) getClass());
    }
}
