package com.holo.framework.horm.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.holo.framework.horm.core.query.DeleteQuery;
import com.holo.framework.horm.core.query.DeleteQueryImpl;
import com.holo.framework.horm.core.query.Query;
import com.holo.framework.horm.core.query.QueryImpl;
import com.holo.framework.horm.core.query.UpdateQuery;
import com.holo.framework.horm.core.query.UpdateQueryImpl;
import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.RelationMeta;
import com.holo.framework.horm.meta.RelationType;
import com.holo.framework.horm.meta.annotation.CascadeType;

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
     * If any relation has {@code CascadeType.PERSIST} or {@code CascadeType.ALL},
     * the related entities are cascaded automatically.
     */
    public final void save() {
        Set<Object> visited = new HashSet<>();
        visited.add(this);
        cascadePersist(this, visited);
    }

    /**
     * Saves this entity along with the specified related entities.
     * Only the named relations are cascaded, regardless of annotation config.
     *
     * @param relationNames the names of relations to cascade
     */
    public final void saveWith(String... relationNames) {
        Set<Object> visited = new HashSet<>();
        visited.add(this);
        cascadePersistNamed(this, Set.of(relationNames), visited);
    }

    /** Deletes this entity by its primary key.
     * If any relation has {@code CascadeType.REMOVE} or {@code CascadeType.ALL},
     * the related entities are cascaded automatically.
     */
    public final void delete() {
        Set<Object> visited = new HashSet<>();
        visited.add(this);
        cascadeDelete(this, visited);
        repository().delete(self());
    }

    /**
     * Deletes this entity along with the specified related entities.
     * Only the named relations are cascaded, regardless of annotation config.
     *
     * @param relationNames the names of relations to cascade
     */
    public final void deleteWith(String... relationNames) {
        Set<Object> visited = new HashSet<>();
        visited.add(this);
        cascadeDeleteNamed(this, relationNames, visited);
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

    /**
     * Active Record fallback stub. Concrete entity classes generated by the
     * {@code holo-horm-maven-plugin} hide this method with a type-safe overload
     * such as {@code public static User find(Object id)}.
     */
    public static <T extends Model<T>> T find(Object id) {
        throw new UnsupportedOperationException(
            "Active Record helper not generated for this entity; "
                + "run 'mvn generate-sources'");
    }

    /**
     * Fetches multiple entities by primary key, returning a map of id → entity.
     *
     * <p>When caching is enabled for the entity type and a {@link CacheChain}
     * is installed on the current {@link HormContext}, this method uses the
     * cache chain's bulk read-through to avoid repeated per-id database
     * round-trips. Absent ids are omitted from the returned map.
     */
    public static <T extends Model<T>> Map<Object, T> findMany(Class<T> type,
                                                               Collection<?> ids) {
        @SuppressWarnings("unchecked")
        Collection<Object> keys = (Collection<Object>) ids;
        return Horm.repository(type).findMany(keys);
    }

    /**
     * Active Record fallback stub. Concrete entity classes generated by the
     * {@code holo-horm-maven-plugin} hide this method with a type-safe overload.
     */
    public static Map<Object, ? extends Model<?>> findMany(Collection<?> ids) {
        throw new UnsupportedOperationException(
            "Active Record helper not generated for this entity; "
                + "run 'mvn generate-sources'");
    }

    /** Fetches all entities of the given type. */
    public static <T extends Model<T>> List<T> all(Class<T> type) {
        return Horm.repository(type).all();
    }

    /**
     * Active Record fallback stub. Concrete entity classes generated by the
     * {@code holo-horm-maven-plugin} hide this method with a type-safe overload.
     */
    public static <T extends Model<T>> List<T> all() {
        throw new UnsupportedOperationException(
            "Active Record helper not generated for this entity; "
                + "run 'mvn generate-sources'");
    }

    /** Returns the total number of persisted entities of the given type. */
    public static <T extends Model<T>> long count(Class<T> type) {
        return Horm.repository(type).count();
    }

    /**
     * Active Record fallback stub. Concrete entity classes generated by the
     * {@code holo-horm-maven-plugin} hide this method with a type-safe overload.
     */
    public static long count() {
        throw new UnsupportedOperationException(
            "Active Record helper not generated for this entity; "
                + "run 'mvn generate-sources'");
    }

    /**
     * Returns a fluent {@link Query} bound to the given entity type and the
     * currently installed {@link HormContext}. The query is mutable; call a
     * terminal method ({@code list}/{@code findFirst}/{@code count}/{@code exists})
     * to execute.
     */
    public static <T extends Model<T>> Query<T> query(Class<T> type) {
        return new QueryImpl<>(type, HormContext.current());
    }

    /**
     * Active Record fallback stub. Concrete entity classes generated by the
     * {@code holo-horm-maven-plugin} hide this method with a type-safe overload.
     */
    public static Query<? extends Model<?>> query() {
        throw new UnsupportedOperationException(
            "Active Record helper not generated for this entity; "
                + "run 'mvn generate-sources'");
    }

    /**
     * Returns a fluent {@link UpdateQuery} for batch-updating entities of the
     * given type.
     */
    public static <T extends Model<T>> UpdateQuery<T> update(Class<T> type) {
        return new UpdateQueryImpl<>(type, HormContext.current());
    }

    /**
     * Active Record fallback stub. Concrete entity classes generated by the
     * {@code holo-horm-maven-plugin} hide this method with a type-safe overload.
     */
    public static UpdateQuery<? extends Model<?>> update() {
        throw new UnsupportedOperationException(
            "Active Record helper not generated for this entity; "
                + "run 'mvn generate-sources'");
    }

    /**
     * Returns a fluent {@link DeleteQuery} for batch-deleting entities of the
     * given type.
     */
    public static <T extends Model<T>> DeleteQuery<T> delete(Class<T> type) {
        return new DeleteQueryImpl<>(type, HormContext.current());
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

    // ===== Cascade helpers =====

    /**
     * Persists {@code entity} and recursively cascades {@code PERSIST}
     * relations. Parents ({@code BELONGS_TO}) are saved first so their ids are
     * available, then the entity itself, then children ({@code HAS_ONE}/
     * {@code HAS_MANY}) with their foreign keys populated.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void cascadePersist(Model entity, Set<Object> visited) {
        EntityMeta meta = EntityMetaRegistry.lookup(entity.getClass());

        // 1. Save parent-side relations first and populate this entity's FK.
        for (Object r : meta.relations()) {
            RelationMeta rel = (RelationMeta) r;
            if (!hasCascadeType(rel, CascadeType.PERSIST)) continue;
            if (rel.type() == RelationType.BELONGS_TO) {
                cascadePersistBelongsTo(entity, rel, visited);
            }
        }

        // 2. Save the entity so its generated id is available for children.
        entity.repository().save(entity);

        // 3. Save child-side relations with the parent's id as foreign key.
        for (Object r : meta.relations()) {
            RelationMeta rel = (RelationMeta) r;
            if (!hasCascadeType(rel, CascadeType.PERSIST)) continue;
            if (rel.type() == RelationType.HAS_ONE || rel.type() == RelationType.HAS_MANY) {
                cascadePersistChildren(entity, rel, visited);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void cascadePersistNamed(Model entity, Set<String> names, Set<Object> visited) {
        EntityMeta meta = EntityMetaRegistry.lookup(entity.getClass());

        for (Object r : meta.relations()) {
            RelationMeta rel = (RelationMeta) r;
            if (!names.contains(rel.name())) continue;
            if (rel.type() == RelationType.BELONGS_TO) {
                cascadePersistBelongsTo(entity, rel, visited);
            }
        }

        entity.repository().save(entity);

        for (Object r : meta.relations()) {
            RelationMeta rel = (RelationMeta) r;
            if (!names.contains(rel.name())) continue;
            if (rel.type() == RelationType.HAS_ONE || rel.type() == RelationType.HAS_MANY) {
                cascadePersistChildren(entity, rel, visited);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void cascadePersistBelongsTo(Model<?> entity, RelationMeta rel, Set<Object> visited) {
        Object related = getRelationValue(entity, rel);
        if (related == null) return;

        Object parentId = null;
        if (related instanceof Collection coll) {
            for (Object item : coll) {
                if (item instanceof Model m) {
                    if (visited.add(m)) {
                        cascadePersist(m, visited);
                    }
                    parentId = m.idValue();
                }
            }
        } else if (related instanceof Model m) {
            if (visited.add(m)) {
                cascadePersist(m, visited);
            }
            parentId = m.idValue();
        }

        if (parentId != null) {
            setForeignKey(entity, entity.getClass(), rel.foreignKey(), parentId);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void cascadePersistChildren(Model<?> entity, RelationMeta rel, Set<Object> visited) {
        Object related = getRelationValue(entity, rel);
        if (related == null) return;

        Object parentId = entity.idValue();
        if (parentId == null) {
            throw new IllegalStateException(
                "Parent id not assigned after save for " + entity.getClass().getName());
        }

        if (related instanceof Collection coll) {
            for (Object item : coll) {
                if (item instanceof Model m) {
                    setForeignKey(m, m.getClass(), rel.foreignKey(), parentId);
                    if (visited.add(m)) {
                        cascadePersist(m, visited);
                    }
                }
            }
        } else if (related instanceof Model m) {
            setForeignKey(m, m.getClass(), rel.foreignKey(), parentId);
            if (visited.add(m)) {
                cascadePersist(m, visited);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void cascadeDelete(Model entity, Set<Object> visited) {
        EntityMeta meta = EntityMetaRegistry.lookup(entity.getClass());
        for (Object r : meta.relations()) {
            RelationMeta rel = (RelationMeta) r;
            if (!hasCascadeType(rel, CascadeType.REMOVE)) continue;
            cascadeDeleteRelation(entity, rel, visited);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void cascadeDeleteNamed(Model entity, String[] relationNames, Set<Object> visited) {
        EntityMeta meta = EntityMetaRegistry.lookup(entity.getClass());
        Set<String> names = Set.of(relationNames);
        for (Object r : meta.relations()) {
            RelationMeta rel = (RelationMeta) r;
            if (!names.contains(rel.name())) continue;
            cascadeDeleteRelation(entity, rel, visited);
        }
    }

    private static void cascadeDeleteRelation(Model<?> entity, RelationMeta rel, Set<Object> visited) {
        Object related = getRelationValue(entity, rel);
        if (related == null) return;
        doCascadeDelete(related, visited);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void doCascadeDelete(Object related, Set<Object> visited) {
        if (related instanceof Collection coll) {
            for (Object item : coll) {
                if (item instanceof Model && visited.add(item)) {
                    Model m = (Model) item;
                    cascadeDelete(m, visited);
                    m.repository().delete(m);
                }
            }
        } else if (related instanceof Model m && visited.add(related)) {
            cascadeDelete(m, visited);
            m.repository().delete(m);
        }
    }

    private static boolean hasCascadeType(RelationMeta rel, CascadeType target) {
        for (CascadeType ct : rel.cascadeTypes()) {
            if (ct == CascadeType.ALL || ct == target) return true;
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object getRelationValue(Model<?> entity, RelationMeta rel) {
        EntityMeta meta = EntityMetaRegistry.lookup(entity.getClass());
        return meta.mapper().getRelation(entity, rel.name());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setForeignKey(Model entity, Class<?> entityType, String fkColumn, Object value) {
        EntityMeta meta = EntityMetaRegistry.lookup(entityType);
        FieldMeta fkField = (FieldMeta) meta.fieldByColumn(fkColumn).orElse(null);
        if (fkField == null) {
            throw new IllegalStateException(
                "Foreign key column '" + fkColumn + "' not found on " + entityType.getName());
        }
        meta.mapper().setField(entity, fkField.name(), value);
    }
}
