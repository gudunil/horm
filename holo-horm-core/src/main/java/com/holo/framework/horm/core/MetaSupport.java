package com.holo.framework.horm.core;

import com.holo.framework.horm.core.datasource.DataSourceRegistry;
import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Row;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Internal helpers for the metadata-derived calculations shared by
 * {@link JdbcRepository} and the query implementations in
 * {@code com.holo.framework.horm.core.query}.
 *
 * <p>Centralizes the small but duplicated logic for resolving the
 * effective datasource name, composing a schema-qualified table
 * identifier, converting the meta-module {@link com.holo.framework.horm.meta.CachePolicy}
 * to the cache-module runtime type, and materializing a {@link Row}
 * from a {@link ResultSet} cursor.
 *
 * <p><strong>Internal API.</strong> Public for cross-package reuse by
 * {@code com.holo.framework.horm.core.query} implementations; not part of
 * the stable HORM contract and may change between releases.
 */
public final class MetaSupport {

    private MetaSupport() {}

    /** Returns the datasource name for {@code meta}, falling back to the default when unset. */
    public static String resolveDataSourceName(EntityMeta<?> meta) {
        String dsName = meta.dataSource();
        return (dsName == null || dsName.isEmpty())
            ? DataSourceRegistry.DEFAULT_NAME
            : dsName;
    }

    /** Returns {@code schema.tableName} when a schema is set, else {@code tableName}. */
    public static String qualifiedTable(EntityMeta<?> meta) {
        String schema = meta.schema();
        return (schema == null || schema.isEmpty())
            ? meta.tableName()
            : schema + "." + meta.tableName();
    }

    /** Converts the meta-module {@link com.holo.framework.horm.meta.CachePolicy} to the cache-module runtime type. */
    public static com.holo.framework.horm.cache.CachePolicy toRuntimePolicy(
        com.holo.framework.horm.meta.CachePolicy metaPolicy) {
        if (metaPolicy == null) {
            return null;
        }
        return com.holo.framework.horm.cache.CachePolicy.builder()
            .ttl(metaPolicy.ttl())
            .evictionPolicy(com.holo.framework.horm.cache.EvictionPolicy.valueOf(metaPolicy.evictionPolicy().name()))
            .maxEntries(metaPolicy.maxEntries())
            .maxWeight(metaPolicy.maxWeight())
            .writeStrategy(com.holo.framework.horm.cache.WriteStrategy.valueOf(metaPolicy.writeStrategy().name()))
            .nullable(metaPolicy.nullable())
            .nullTtl(metaPolicy.nullTtl())
            .build();
    }

    /** Materializes a {@link Row} from the current cursor position of {@code rs} using {@code meta}'s fields. */
    public static Row toRow(ResultSet rs, EntityMeta<?> meta) throws SQLException {
        Row row = Row.create(meta.tableName());
        for (FieldMeta<?> fd : meta.fields()) {
            row.set(fd.column(), rs.getObject(fd.column()));
        }
        return row;
    }
}
