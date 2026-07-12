package com.holo.framework.horm.benchmark.entity;

import java.time.Instant;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.meta.annotation.CachePolicy;
import com.holo.framework.horm.meta.annotation.Cached;
import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.CacheLevel;
import com.holo.framework.horm.meta.annotation.GenerationType;
import com.holo.framework.horm.meta.annotation.Id;

/**
 * HORM entity with L1 caching enabled for {@code CacheBenchmark}. Shares the
 * {@code bench_users} table with {@link BenchUser} — safe because the cache
 * benchmark is read-only.
 */
@Entity(table = "bench_users")
@Cached(levels = {CacheLevel.L1}, policy = @CachePolicy(ttl = "30m"))
public class CachedBenchUser extends Model<CachedBenchUser> {

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
