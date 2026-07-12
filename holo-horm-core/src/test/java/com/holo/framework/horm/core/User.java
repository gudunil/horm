package com.holo.framework.horm.core;

import java.time.Instant;

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
    }
