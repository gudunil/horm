package com.holo.framework.horm.core.optimistic;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.query.Query;
import com.holo.framework.horm.core.query.UpdateQuery;
import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.GenerationType;
import com.holo.framework.horm.meta.annotation.Id;
import com.holo.framework.horm.meta.annotation.Version;

@Entity(table = "versioned_items")
public class VersionedItem extends Model<VersionedItem> {
    @Id(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String name;
    @Version
    private Long version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public static VersionedItem find(Object id) {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static Map<Object, VersionedItem> findMany(Collection<?> ids) {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static List<VersionedItem> all() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static long count() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static Query<VersionedItem> query() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static UpdateQuery<VersionedItem> update() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }
}
