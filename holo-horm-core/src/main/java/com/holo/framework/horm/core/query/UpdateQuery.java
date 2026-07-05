package com.holo.framework.horm.core.query;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.meta.query.Condition;
import com.holo.framework.horm.meta.query.TypedField;

/**
 * Type-safe fluent API for batch UPDATE operations bound to entity type {@code T}.
 *
 * <p>Set clause values are built from APT-generated {@code XxxQueryMeta}
 * {@link TypedField} constants. Where conditions use the same
 * {@link Condition} infrastructure as {@link Query}.
 *
 * <p>Builder methods ({@code set}/{@code where}/{@code and}) are mutable
 * and return {@code this} for chaining. The terminal {@link #execute()}
 * method runs the UPDATE and returns the number of affected rows.
 *
 * <pre>{@code
 * int updated = Model.update(Product.class)
 *     .set(ProductQueryMeta.PRICE, 29.99)
 *     .where(ProductQueryMeta.CATEGORY.eq("books"))
 *     .execute();
 * }</pre>
 *
 * @param <T> entity type
 */
public interface UpdateQuery<T extends Model<T>> {

    /**
     * Add a SET clause entry.
     *
     * @param field the entity field to update
     * @param value the new value (bound via {@code ?} placeholder)
     */
    UpdateQuery<T> set(TypedField<T, ?> field, Object value);

    /** Append conditions combined with implicit AND. No-op for empty input. */
    UpdateQuery<T> where(Condition... conditions);

    /** Alias of {@link #where} for chained readability. */
    UpdateQuery<T> and(Condition... conditions);

    /**
     * Execute the UPDATE and return the number of affected rows.
     *
     * <p>When no SET entries have been added, throws
     * {@link com.holo.framework.horm.core.HormException}.
     * When no WHERE conditions are specified, all rows are updated.
     */
    int execute();
}
