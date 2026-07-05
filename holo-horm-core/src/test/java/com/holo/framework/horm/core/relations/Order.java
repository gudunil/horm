package com.holo.framework.horm.core.relations;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.meta.annotation.BelongsTo;
import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.GenerationType;
import com.holo.framework.horm.meta.annotation.Id;

import java.math.BigDecimal;
import java.util.List;

/**
 * M3 integration-test entity. Plays three roles:
 * <ul>
 *   <li>Target of {@code @HasMany} from {@link UserWithRelations} (via
 *       {@code user_id}).</li>
 *   <li>Owner of {@code @BelongsTo} pointing back to
 *       {@link UserWithRelations} (via {@code user_id}; R5 forces List
 *       semantics so {@code getParentUsers} returns a single-element list
 *       containing the parent user).</li>
 *   <li>Through entity for {@code @HasManyThrough} from
 *       {@link UserWithRelations} to {@link Product} (via
 *       {@code user_id} → {@code product_id}).</li>
 * </ul>
 */
@Entity(table = "orders")
public class Order extends Model<Order> {

    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "product_id")
    private Long productId;

    @Column
    private BigDecimal amount;

    @BelongsTo(targetEntity = UserWithRelations.class, foreignKey = "user_id")
    private List<UserWithRelations> parentUsers;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public List<UserWithRelations> getParentUsers() { return parentUsers; }
    public void setParentUsers(List<UserWithRelations> parentUsers) { this.parentUsers = parentUsers; }
}
