package com.holo.framework.horm.core.relations;

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

/**
 * M3 integration-test entity: the "child" on the owning side of a
 * {@code @HasOne} relation from {@link UserWithRelations}. Holds the
 * {@code user_id} foreign key.
 */
@Entity(table = "profiles")
public class Profile extends Model<Profile> {

    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column
    private String name;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public static Profile find(Object id) {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static Map<Object, Profile> findMany(Collection<?> ids) {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static List<Profile> all() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static long count() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static Query<Profile> query() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static UpdateQuery<Profile> update() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }
}
