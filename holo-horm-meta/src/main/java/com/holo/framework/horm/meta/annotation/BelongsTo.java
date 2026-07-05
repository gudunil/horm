package com.holo.framework.horm.meta.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as the owning side of a belongs-to relation: this entity
 * holds the foreign key column pointing to the parent's primary key.
 *
 * <p>Example:
 * <pre>{@code
 * @Entity
 * public class Order extends Model<Order> {
 *     @BelongsTo(targetEntity = User.class, foreignKey = "user_id")
 *     private List<User> user;
 * }
 * }</pre>
 *
 * <p>If {@link #foreignKey()} is omitted it defaults to
 * {@code <targetSimpleLower>_id} (e.g. {@code user_id}).
 *
 * @see HasOne
 * @see HasMany
 * @see HasAndBelongsToMany
 * @see HasManyThrough
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BelongsTo {

    /** The parent entity type this entity belongs to. */
    Class<?> targetEntity();

    /** Foreign-key column on this entity. Empty means derive from target simple name. */
    String foreignKey() default "";

    /** Cascade operations to apply when the parent entity is persisted/removed. */
    CascadeType[] cascade() default {};
}
