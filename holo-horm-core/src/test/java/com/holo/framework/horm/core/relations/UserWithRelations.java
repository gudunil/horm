package com.holo.framework.horm.core.relations;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.meta.annotation.CascadeType;
import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.HasAndBelongsToMany;
import com.holo.framework.horm.meta.annotation.HasMany;
import com.holo.framework.horm.meta.annotation.HasManyThrough;
import com.holo.framework.horm.meta.annotation.HasOne;
import com.holo.framework.horm.meta.annotation.GenerationType;
import com.holo.framework.horm.meta.annotation.Id;

import java.util.List;

/**
 * M3 integration-test entity exercising all four parent-side relation
 * types. Independent from {@code User.java} to avoid M1/M2 regression.
 *
 * <ul>
 *   <li>{@code @HasOne(Profile)} — User has one Profile (Profile holds
 *       {@code user_id}).</li>
 *   <li>{@code @HasMany(Order)} — User has many Orders (Order holds
 *       {@code user_id}).</li>
 *   <li>{@code @HasAndBelongsToMany(Tag)} via {@code user_tags_rel} join
 *       table.</li>
 *   <li>{@code @HasManyThrough(Product)} through {@link Order} (Order
 *       holds {@code user_id} + {@code product_id}).</li>
 * </ul>
 *
 * <p>The fifth relation type {@code @BelongsTo} lives on {@link Order}
 * (the child side) and is exercised by querying Order and fetching
 * {@code OrderQueryMeta.PARENT_USERS}.
 */
@Entity(table = "users_with_relations")
public class UserWithRelations extends Model<UserWithRelations> {

    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String email;

    @HasOne(targetEntity = Profile.class, foreignKey = "user_id", cascade = CascadeType.ALL)
    private List<Profile> profiles;

    @HasMany(targetEntity = Order.class, foreignKey = "user_id", cascade = CascadeType.ALL)
    private List<Order> orders;

    @HasAndBelongsToMany(
        targetEntity = Tag.class,
        joinTable = "user_tags_rel",
        foreignKey = "user_id",
        associationForeignKey = "tag_id")
    private List<Tag> tags;

    @HasManyThrough(
        targetEntity = Product.class,
        through = Order.class,
        foreignKey = "user_id",
        associationForeignKey = "product_id")
    private List<Product> products;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public List<Profile> getProfiles() { return profiles; }
    public void setProfiles(List<Profile> profiles) { this.profiles = profiles; }

    public List<Order> getOrders() { return orders; }
    public void setOrders(List<Order> orders) { this.orders = orders; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }

    public List<Product> getProducts() { return products; }
    public void setProducts(List<Product> products) { this.products = products; }
}
