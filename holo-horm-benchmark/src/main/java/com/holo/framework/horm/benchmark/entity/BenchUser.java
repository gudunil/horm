package com.holo.framework.horm.benchmark.entity;

import java.time.Instant;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.GenerationType;
import com.holo.framework.horm.meta.annotation.Id;

/**
 * HORM Active Record entity for JMH benchmarks. Placed in {@code src/main/java}
 * so the ByteBuddy {@code process-classes} instrumentation fires before
 * test compilation, allowing {@code BenchUser.find(id)} to bind to the
 * generated static overload instead of the {@code Model.find(Object)} fallback.
 */
@Entity(table = "bench_users")
public class BenchUser extends Model<BenchUser> {

    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 255)
    private String email;

    @Column(length = 255)
    private String name;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
