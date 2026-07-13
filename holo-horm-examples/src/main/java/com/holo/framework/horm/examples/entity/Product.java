package com.holo.framework.horm.examples.entity;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.meta.annotation.CacheLevel;
import com.holo.framework.horm.meta.annotation.CachePolicy;
import com.holo.framework.horm.meta.annotation.Cached;
import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.GenerationType;
import com.holo.framework.horm.meta.annotation.Id;
import com.holo.framework.horm.meta.annotation.WriteStrategy;

import java.math.BigDecimal;

/**
 * 示例商品实体，演示 L1 缓存与写穿透策略。
 */
@Entity(table = "products")
@Cached(levels = {CacheLevel.L1},
        policy = @CachePolicy(ttl = "30m", maxEntries = 1000,
                              writeStrategy = WriteStrategy.THROUGH))
public class Product extends Model<Product> {

    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, unique = true)
    private String sku;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}
