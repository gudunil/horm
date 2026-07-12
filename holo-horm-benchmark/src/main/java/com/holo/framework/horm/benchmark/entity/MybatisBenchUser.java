package com.holo.framework.horm.benchmark.entity;

import java.time.Instant;

/**
 * Plain POJO for MyBatis benchmarks. No annotations — mapping is driven by
 * {@code mybatis/BenchUserMapper.xml}.
 */
public class MybatisBenchUser {

    private Long id;
    private String email;
    private String name;
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
