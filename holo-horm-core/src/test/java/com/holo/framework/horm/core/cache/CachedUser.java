package com.holo.framework.horm.core.cache;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.query.Query;
import com.holo.framework.horm.core.query.UpdateQuery;
import com.holo.framework.horm.meta.annotation.CacheLevel;
import com.holo.framework.horm.meta.annotation.CachePolicy;
import com.holo.framework.horm.meta.annotation.Cached;
import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.GenerationType;
import com.holo.framework.horm.meta.annotation.Id;
import com.holo.framework.horm.meta.annotation.WriteStrategy;

/**
 * Cache-aware integration-test entity for M6 batch-loading tests.
 *
 * <p>Annotated with {@code @Cached} so the APT processor generates
 * {@code CachedUserMeta} with {@code cached=true} and a THROUGH write
 * strategy, enabling the cache chain in {@code JdbcRepository}.
 */
@Entity(table = "cached_users")
@Cached(
    levels = {CacheLevel.L1},
    policy = @CachePolicy(
        ttl = "30m",
        writeStrategy = WriteStrategy.THROUGH,
        maxEntries = 1000
    )
)
public class CachedUser extends Model<CachedUser> {

    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public static CachedUser find(Object id) {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static Map<Object, CachedUser> findMany(Collection<?> ids) {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static List<CachedUser> all() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static long count() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static Query<CachedUser> query() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static UpdateQuery<CachedUser> update() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }
}
