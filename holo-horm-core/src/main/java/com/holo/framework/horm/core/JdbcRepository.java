package com.holo.framework.horm.core;

import com.holo.framework.horm.cache.CacheChain;
import com.holo.framework.horm.cache.CachePolicy;
import com.holo.framework.horm.cache.TypeReference;
import com.holo.framework.horm.cache.key.CacheKey;
import com.holo.framework.horm.cache.key.CacheKeyBuilder;
import com.holo.framework.horm.core.dialect.Dialect;
import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.Row;
import com.holo.framework.horm.meta.annotation.GenerationType;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JDBC-backed {@link Repository} implementation that drives all CRUD
 * operations through parameterized {@link PreparedStatement}s.
 *
 * <p>SQL is composed at call time from the {@link EntityMeta} registered
 * for {@code T}: table name, id column, and the insertable/updatable field
 * columns are all read from compile-time-generated metadata, so no
 * reflection is involved on the hot path. Every user-supplied value is
 * bound via a {@code ?} placeholder, eliminating SQL injection risk —
 * the only string concatenation is over APT-frozen column identifiers.
 *
 * <p>{@link #save(Object)} auto-detects INSERT (when {@code mapper.getId}
 * returns {@code null}) vs UPDATE (id non-null). INSERT executes with
 * {@link Statement#RETURN_GENERATED_KEYS} and backfills the generated
 * primary key onto the entity via {@link Mapper#setId}.
 *
 * <p>M6 integrates the optional {@link CacheChain} installed on the
 * {@link HormContext}. When the entity is annotated with {@code @Cached}
 * and a chain is present, reads go through the cache first and writes
 * schedule invalidation or population via
 * {@link TransactionManager#afterCommit(Runnable)}. When caching is not
 * enabled, all cache code short-circuits and behaviour matches M5.
 *
 * <p>All {@link SQLException}s are wrapped in {@link HormException} so
 * callers can stay block-free while still catching HORM-specific failures.
 *
 * @param <T> entity type
 */
public final class JdbcRepository<T extends Model<T>> implements Repository<T> {

    private final Class<T> entityType;
    private final HormContext ctx;
    private final EntityMeta<T> meta;
    private final Mapper<T> mapper;
    private final String dataSourceName;
    private final TypeReference<T> entityTypeRef = new TypeReference<>() {};
    private final CachePolicy runtimeCachePolicy;
    private final SqlTemplates sqlTemplates;

    public JdbcRepository(Class<T> entityType, HormContext ctx) {
        this.entityType = entityType;
        this.ctx = ctx;
        this.meta = EntityMetaRegistry.lookup(entityType);
        this.mapper = meta.mapper();
        this.dataSourceName = MetaSupport.resolveDataSourceName(meta);
        this.runtimeCachePolicy = MetaSupport.toRuntimePolicy(meta.cachePolicy());
        this.sqlTemplates = new SqlTemplates(meta);
    }

    @Override
    public T find(Object id) {
        if (!cacheEnabled()) {
            return dbFind(id);
        }
        CacheKey key = idKey(id);
        return ctx.cacheChain()
            .get(key, entityTypeRef, () -> dbFind(id), runtimeCachePolicy)
            .orElse(null);
    }

    /** Database lookup backing {@link #find(Object)} and the cache loader. */
    private T dbFind(Object id) {
        requireIdField();
        return JdbcOperations.query(ctx, dataSourceName, sqlTemplates.findById(), List.of(id),
            rs -> rs.next() ? mapper.map(MetaSupport.toRow(rs, meta)) : null,
            "find " + entityType.getName() + " by id " + id);
    }

    @Override
    public List<T> all() {
        return JdbcOperations.query(ctx, dataSourceName, sqlTemplates.findAll(), List.of(),
            rs -> {
                List<T> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapper.map(MetaSupport.toRow(rs, meta)));
                }
                return result;
            },
            "fetch all " + entityType.getName());
    }

    @Override
    public long count() {
        return JdbcOperations.query(ctx, dataSourceName, sqlTemplates.count(), List.of(),
            rs -> rs.next() ? rs.getLong(1) : 0L,
            "count " + entityType.getName());
    }

    @Override
    public boolean exists(Object id) {
        FieldMeta<?> idField = requireIdField();
        StringBuilder sqlBuilder = new StringBuilder("SELECT 1 FROM ").append(MetaSupport.qualifiedTable(meta))
            .append(" WHERE ").append(idField.column()).append(" = ?");
        List<Object> bindings = new ArrayList<>();
        bindings.add(id);
        Dialect dialect = ctx.dialect(dataSourceName);
        dialect.paginate(sqlBuilder, bindings, 0L, 1L);
        return JdbcOperations.query(ctx, dataSourceName, sqlBuilder.toString(), bindings,
            ResultSet::next,
            "check existence of " + entityType.getName() + " by id " + id);
    }

    @Override
    public T save(T entity) {
        Object id = mapper.getId(entity);
        if (id == null) {
            return insert(entity);
        }
        return isManualId() ? insert(entity) : update(entity);
    }

    private boolean isManualId() {
        FieldMeta<?> idField = meta.idField();
        return idField != null && idField.generationStrategy() == GenerationType.MANUAL;
    }

    private T insert(T entity) {
        requireIdField();
        List<String> columns = sqlTemplates.insertColumns();
        Row row = mapper.toRow(entity);
        List<Object> bindings = columns.stream().map(row::get).toList();
        JdbcOperations.insert(ctx, dataSourceName, sqlTemplates.insert(), bindings,
            isManualId() ? null : generatedId -> mapper.setId(entity, generatedId),
            "insert " + entityType.getName());

        if (cacheEnabled() && runtimeCachePolicy.writeStrategy() != com.holo.framework.horm.cache.WriteStrategy.AROUND) {
            CacheKey key = idKey(mapper.getId(entity));
            TransactionManager.afterCommit(dataSourceName, () -> ctx.cacheChain().put(key, entity, runtimeCachePolicy));
        }
        return entity;
    }

    private T update(T entity) {
        FieldMeta<?> idField = requireIdField();
        FieldMeta<?> versionField = meta.versionField();

        List<String> columns = meta.fields().stream()
            .filter(FieldMeta::updatable)
            .filter(f -> !f.isId())
            .filter(f -> !f.version())
            .map(FieldMeta::column)
            .toList();

        String setClause = columns.stream()
            .map(c -> c + " = ?")
            .collect(Collectors.joining(", "));

        StringBuilder sql = new StringBuilder("UPDATE ")
            .append(MetaSupport.qualifiedTable(meta))
            .append(" SET ");

        List<Object> bindings = new ArrayList<>();
        Row row = mapper.toRow(entity);

        if (columns.isEmpty() && versionField == null) {
            throw new IllegalStateException(
                "No updatable fields for " + entityType.getName() + "; nothing to update");
        }

        if (!columns.isEmpty()) {
            sql.append(setClause);
            for (String col : columns) {
                bindings.add(row.get(col));
            }
        }

        // Version column: SET version = version + 1
        if (versionField != null) {
            if (!columns.isEmpty()) {
                sql.append(", ");
            }
            sql.append(versionField.column()).append(" = ").append(versionField.column()).append(" + 1");
        }

        sql.append(" WHERE ").append(idField.column()).append(" = ?");
        bindings.add(mapper.getId(entity));

        // Version check: AND version = ?
        if (versionField != null) {
            sql.append(" AND ").append(versionField.column()).append(" = ?");
            bindings.add(mapper.getField(entity, versionField.name()));
        }

        int affected = JdbcOperations.update(ctx, dataSourceName, sql.toString(), bindings,
            "update " + entityType.getName());
        if (versionField != null && affected == 0) {
            throw new OptimisticLockException(
                "Optimistic lock failed for " + entityType.getName()
                    + " with id=" + mapper.getId(entity));
        }
        if (versionField != null) {
            mapper.incrementVersion(entity);
        }

        if (cacheEnabled()) {
            CacheKey key = idKey(mapper.getId(entity));
            if (runtimeCachePolicy.writeStrategy() == com.holo.framework.horm.cache.WriteStrategy.THROUGH) {
                TransactionManager.afterCommit(dataSourceName, () -> ctx.cacheChain().put(key, entity, runtimeCachePolicy));
            } else {
                TransactionManager.afterCommit(dataSourceName, () -> ctx.cacheChain().invalidate(key));
            }
        }
        return entity;
    }

    @Override
    public void delete(T entity) {
        FieldMeta<?> versionField = meta.versionField();
        Object id = mapper.getId(entity);
        if (versionField != null) {
            requireIdField();
            List<Object> bindings = List.of(id, mapper.getField(entity, versionField.name()));
            int affected = JdbcOperations.update(ctx, dataSourceName,
                sqlTemplates.deleteByIdAndVersion(), bindings,
                "delete " + entityType.getName() + " by id " + id);
            if (affected == 0) {
                throw new OptimisticLockException(
                    "Optimistic lock failed on delete for " + entityType.getName()
                        + " with id=" + id);
            }
            invalidateCache(id);
        } else {
            deleteById(id);
        }
    }

    @Override
    public void deleteById(Object id) {
        requireIdField();
        JdbcOperations.update(ctx, dataSourceName, sqlTemplates.deleteById(), List.of(id),
            "delete " + entityType.getName() + " by id " + id);
        invalidateCache(id);
    }

    @Override
    public List<T> batchInsert(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        requireIdField();
        List<String> columns = sqlTemplates.insertColumns();
        List<List<Object>> batchBindings = new ArrayList<>(entities.size());
        for (T entity : entities) {
            Row row = mapper.toRow(entity);
            batchBindings.add(columns.stream().map(row::get).toList());
        }
        final int[] index = {0};
        JdbcOperations.batchInsert(ctx, dataSourceName, sqlTemplates.insert(), batchBindings,
            isManualId() ? null : generatedId -> mapper.setId(entities.get(index[0]++), generatedId),
            "batch insert " + entityType.getName());

        if (cacheEnabled() && runtimeCachePolicy.writeStrategy() != com.holo.framework.horm.cache.WriteStrategy.AROUND) {
            for (T entity : entities) {
                CacheKey key = idKey(mapper.getId(entity));
                TransactionManager.afterCommit(dataSourceName,
                    () -> ctx.cacheChain().put(key, entity, runtimeCachePolicy));
            }
        }
        return entities;
    }

    @Override
    public Map<Object, T> findMany(Collection<Object> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        if (!cacheEnabled()) {
            return dbFindMany(ids);
        }

        Map<CacheKey, Object> keyToId = new LinkedHashMap<>();
        for (Object id : ids) {
            keyToId.put(idKey(id), id);
        }
        Set<CacheKey> keys = keyToId.keySet();

        Map<CacheKey, T> cached = ctx.cacheChain()
            .getAll(keys, entityTypeRef, this::dbFindManyByKey, runtimeCachePolicy);

        Map<Object, T> result = new LinkedHashMap<>();
        for (Map.Entry<CacheKey, T> e : cached.entrySet()) {
            Object id = keyToId.get(e.getKey());
            if (id != null && e.getValue() != null) {
                result.put(id, e.getValue());
            }
        }
        return result;
    }

    /** Database batch lookup backing {@link #findMany(Collection)}. */
    private Map<Object, T> dbFindMany(Collection<Object> ids) {
        FieldMeta<?> idField = requireIdField();
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(", "));
        String sql = "SELECT * FROM " + MetaSupport.qualifiedTable(meta)
            + " WHERE " + idField.column() + " IN (" + placeholders + ")";
        return JdbcOperations.query(ctx, dataSourceName, sql, List.copyOf(ids),
            rs -> {
                Map<Object, T> result = new LinkedHashMap<>();
                while (rs.next()) {
                    T entity = mapper.map(MetaSupport.toRow(rs, meta));
                    result.put(mapper.getId(entity), entity);
                }
                return result;
            },
            "findMany " + entityType.getName() + " by ids " + ids);
    }

    /**
     * Batch loader used by the cache chain. The input keys are
     * {@link CacheKey}s; the returned map must be keyed by the same keys.
     */
    private Map<CacheKey, T> dbFindManyByKey(Set<CacheKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        // Build a mapping from the string representation of the id (as it
        // appears in CacheKey.keyValue()) to the CacheKey itself. We use
        // String.valueOf() on the database-returned id to match the key
        // lookup, since CacheKey stores the id as a string.
        List<Object> ids = new ArrayList<>(keys.size());
        Map<String, CacheKey> idStringToKey = new HashMap<>();
        for (CacheKey key : keys) {
            Object id = key.keyValue();
            ids.add(id);
            idStringToKey.put(String.valueOf(id), key);
        }
        Map<Object, T> byId = dbFindMany(ids);
        Map<CacheKey, T> result = new LinkedHashMap<>();
        for (Map.Entry<Object, T> e : byId.entrySet()) {
            // Normalize the database-returned id to its string form to match
            // the CacheKey's keyValue() representation. This handles cases
            // where the id type differs (e.g., Long from DB vs String in key).
            CacheKey key = idStringToKey.get(String.valueOf(e.getKey()));
            if (key != null) {
                result.put(key, e.getValue());
            }
        }
        return result;
    }

    /** Schedules cache invalidation for the given id after transaction commit. */
    private void invalidateCache(Object id) {
        if (!cacheEnabled()) {
            return;
        }
        CacheKey key = idKey(id);
        TransactionManager.afterCommit(dataSourceName, () -> ctx.cacheChain().invalidate(key));
    }

    /** Builds a primary-key {@link CacheKey} for this entity type. */
    private CacheKey idKey(Object id) {
        return new CacheKeyBuilder()
            .entityType(entityType)
            .idKey(id)
            .build();
    }

    /** Returns {@code true} when caching is configured for this entity and a chain is installed. */
    private boolean cacheEnabled() {
        return meta.cached() && ctx.cacheChain() != null;
    }

    private FieldMeta<?> requireIdField() {
        FieldMeta<?> idField = meta.idField();
        if (idField == null) {
            throw new HormException("Entity " + entityType.getName() + " has no @Id field");
        }
        return idField;
    }

}
