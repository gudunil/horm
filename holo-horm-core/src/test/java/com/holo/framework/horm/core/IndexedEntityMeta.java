package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.Row;

import java.util.List;

/**
 * Hand-written stand-in for an APT-generated {@code XxxMeta} class.
 *
 * <p>Listed in {@code META-INF/horm/entities.idx} so that
 * {@link EntityMetaRegistry#loadIndex()} discovers it via
 * {@code ClassLoader.getResources}, invokes {@link #entityMeta()} by
 * reflection, and registers the resulting {@link EntityMeta} keyed by
 * {@link IndexedEntity}.
 */
public final class IndexedEntityMeta {

    private IndexedEntityMeta() {
    }

    public static EntityMeta<IndexedEntity> entityMeta() {
        return EntityMeta.<IndexedEntity>builder()
            .type(IndexedEntity.class)
            .tableName("indexed_entities")
            .fields(List.of())
            .mapper(new IndexedEntityMapper())
            .build();
    }

    /** Minimal {@link Mapper} for {@link IndexedEntity}. */
    static final class IndexedEntityMapper implements Mapper<IndexedEntity> {
        @Override
        public IndexedEntity map(Row row) {
            return new IndexedEntity();
        }

        @Override
        public Row toRow(IndexedEntity entity) {
            return Row.create("indexed_entities");
        }

        @Override
        public Object getId(IndexedEntity entity) {
            return null;
        }

        @Override
        public void setId(IndexedEntity entity, Object id) {
            // no-op
        }

        @Override
        public Object getField(IndexedEntity entity, String field) {
            throw new IllegalArgumentException("Unknown field: " + field);
        }

        @Override
        public void setField(IndexedEntity entity, String field, Object value) {
            throw new IllegalArgumentException("Unknown field: " + field);
        }
    }
}
