package com.holo.framework.horm.meta.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as a many-to-many relation via a join table. The join table
 * has two foreign-key columns: one pointing to this entity's primary key
 * ({@link #foreignKey()}) and one pointing to the target's primary key
 * ({@link #associationForeignKey()}).
 *
 * <p>Example:
 * <pre>{@code
 * @Entity
 * public class User extends Model<User> {
 *     @HasAndBelongsToMany(
 *         targetEntity = Tag.class,
 *         joinTable = "user_tags",
 *         foreignKey = "user_id",
 *         associationForeignKey = "tag_id"
 *     )
 *     private List<Tag> tags;
 * }
 * }</pre>
 *
 * <p>{@link #joinTable()} is required — the validator (R7) reports a
 * compile error if it is empty. If {@link #foreignKey()} or
 * {@link #associationForeignKey()} is omitted they default to
 * {@code <ownerSimpleLower>_id} and {@code <targetSimpleLower>_id}
 * respectively.
 *
 * @see BelongsTo
 * @see HasOne
 * @see HasMany
 * @see HasManyThrough
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HasAndBelongsToMany {

    /** The target entity type. */
    Class<?> targetEntity();

    /** Name of the join table. Required (R7). */
    String joinTable() default "";

    /** Foreign-key column on the join table pointing to this entity. Empty means derive from owner simple name. */
    String foreignKey() default "";

    /** Foreign-key column on the join table pointing to the target entity. Empty means derive from target simple name. */
    String associationForeignKey() default "";
}
