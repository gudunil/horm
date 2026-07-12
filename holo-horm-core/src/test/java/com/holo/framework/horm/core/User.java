package com.holo.framework.horm.core;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.holo.framework.horm.core.query.Query;
import com.holo.framework.horm.core.query.UpdateQuery;
import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.GenerationType;
import com.holo.framework.horm.meta.annotation.Id;

/**
 * Integration-test entity exercising the full APT → registry → JDBC pipeline
 * against an in-memory H2 database. Annotated with {@code @Entity} so the
 * compiler invokes {@code HormEntityProcessor}, which generates {@code UserMeta},
 * {@code UserMapper}, and {@code UserQueryMeta} under {@code core.generated}.
 */
@Entity(table = "users")
public class User extends Model<User> {

    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String email;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public static User find(Object id) {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static Map<Object, User> findMany(Collection<?> ids) {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static List<User> all() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static long count() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static Query<User> query() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static UpdateQuery<User> update() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }
}
