package com.holo.framework.horm.meta.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as a has-many-through relation: this entity has many targets
 * through an intermediate (through) entity. The through entity holds a
 * foreign key to this entity, and also holds a foreign key to the target.
 *
 * <p>Example (User has many Products through Order):
 * <pre>{@code
 * @Entity
 * public class User extends Model<User> {
 *     @HasManyThrough(
 *         targetEntity = Product.class,
 *         through = Order.class,
 *         foreignKey = "user_id",        // Order's column pointing to User
 *         associationForeignKey = "product_id"  // Order's column pointing to Product
 *     )
 *     private List<Product> products;
 * }
 * }</pre>
 *
 * <p>{@link #through()} is required — the validator (R8) reports a compile
 * error if it is {@code void.class}. If {@link #foreignKey()} or
 * {@link #associationForeignKey()} is omitted they default to
 * {@code <ownerSimpleLower>_id} and {@code <targetSimpleLower>_id}
 * respectively.
 *
 * @see BelongsTo
 * @see HasOne
 * @see HasMany
 * @see HasAndBelongsToMany
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HasManyThrough {

    /** The final target entity type. */
    Class<?> targetEntity();

    /** The intermediate entity type. Required (R8). */
    Class<?> through() default void.class;

    /** Foreign-key column on the through entity pointing to this entity. Empty means derive from owner simple name. */
    String foreignKey() default "";

    /** Foreign-key column on the through entity pointing to the target. Empty means derive from target simple name. */
    String associationForeignKey() default "";
}
