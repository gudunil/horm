package com.holo.framework.horm.meta.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as a has-many relation: the target entity holds the foreign
 * key column pointing back to this entity's primary key, and there may be
 * many such targets per parent.
 *
 * <p>Example:
 * <pre>{@code
 * @Entity
 * public class User extends Model<User> {
 *     @HasMany(targetEntity = Order.class, foreignKey = "user_id")
 *     private List<Order> orders;
 * }
 * }</pre>
 *
 * <p>If {@link #foreignKey()} is omitted it defaults to
 * {@code <ownerSimpleLower>_id} (e.g. {@code user_id}).
 *
 * @see BelongsTo
 * @see HasOne
 * @see HasAndBelongsToMany
 * @see HasManyThrough
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HasMany {

    /** The child entity type this entity owns many of. */
    Class<?> targetEntity();

    /** Foreign-key column on the target entity. Empty means derive from owner simple name. */
    String foreignKey() default "";

    /** Cascade operations to apply from the owner to the targets. */
    CascadeType[] cascade() default {};
}
