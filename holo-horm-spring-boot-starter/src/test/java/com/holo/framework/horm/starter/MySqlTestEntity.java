package com.holo.framework.horm.starter;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.GenerationType;
import com.holo.framework.horm.meta.annotation.Id;

import java.time.Instant;

/**
 * MySQL 集成测试实体，用于验证 HORM 框架在真实 MySQL 数据库下的 CRUD 行为。
 *
 * <p>通过 APT 生成 {@code MySqlTestEntityMeta}、{@code MySqlTestEntityMapper}
 * 和 {@code MySqlTestEntityQueryMeta}，由 {@code EntityMetaRegistry} 在首次查询时加载。
 */
@Entity(table = "mysql_test_entities")
public class MySqlTestEntity extends Model<MySqlTestEntity> {

    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
