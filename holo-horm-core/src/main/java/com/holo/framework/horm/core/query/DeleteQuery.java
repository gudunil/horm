package com.holo.framework.horm.core.query;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.meta.query.Condition;

/**
 * Type-safe fluent API for batch DELETE operations bound to entity type {@code T}.
 *
 * <p>Where conditions use the same {@link Condition} infrastructure as
 * {@link Query}. Builder methods ({@code where}/{@code and}) are mutable
 * and return {@code this} for chaining. The terminal {@link #execute()}
 * method runs the DELETE and returns the number of affected rows.
 *
 * <pre>{@code
 * int deleted = Model.delete(Product.class)
 *     .where(ProductQueryMeta.CATEGORY.eq("obsolete"))
 *     .execute();
 * }</pre>
 *
 * @param <T> entity type
 */
public interface DeleteQuery<T extends Model<T>> {

    /** Append conditions combined with implicit AND. No-op for empty input. */
    DeleteQuery<T> where(Condition... conditions);

    /** Alias of {@link #where} for chained readability. */
    DeleteQuery<T> and(Condition... conditions);

    /**
     * Execute the DELETE and return the number of affected rows.
     *
     * <p>When no WHERE conditions are specified, all rows are deleted.
     */
    int execute();
}
