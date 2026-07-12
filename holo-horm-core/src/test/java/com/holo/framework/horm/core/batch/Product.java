package com.holo.framework.horm.core.batch;

import java.math.BigDecimal;
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

@Entity(table = "products")
public class Product extends Model<Product> {
    @Id(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String category;
    @Column(nullable = false)
    private BigDecimal price;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public static Product find(Object id) {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static Map<Object, Product> findMany(Collection<?> ids) {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static List<Product> all() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static long count() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static Query<Product> query() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }

    public static UpdateQuery<Product> update() {
        throw new UnsupportedOperationException("Instrumented by HORM ByteBuddy plugin");
    }
}
