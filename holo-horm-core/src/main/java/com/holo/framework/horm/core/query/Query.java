package com.holo.framework.horm.core.query;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.meta.query.Condition;
import com.holo.framework.horm.meta.query.RelationField;
import com.holo.framework.horm.meta.query.TypedField;

import java.util.List;
import java.util.Optional;

/**
 * Type-safe fluent query API bound to an entity type {@code T}. Conditions
 * are built from APT-generated {@code XxxQueryMeta} {@link TypedField}
 * constants and rendered through {@link Condition#sqlFragment()} /
 * {@link Condition#bindings()}.
 *
 * <p>Builder methods ({@code where}/{@code and}/{@code or}/{@code orderBy}/
 * {@code limit}/{@code offset}) are mutable and return {@code this} for
 * chaining. Terminal methods ({@code list}/{@code findFirst}/{@code count}/
 * {@code exists}) execute the query against the current
 * {@link com.holo.framework.horm.core.HormContext} and cannot be called twice
 * with different state on the same instance — build a fresh {@code Query} for
 * a different shape.
 *
 * <pre>{@code
 * List<User> users = Model.query(User.class)
 *     .where(UserQueryMeta.EMAIL.like("%@holo.dev"))
 *     .and(UserQueryMeta.ID.gt(100))
 *     .orderBy(UserQueryMeta.CREATED_AT, Order.DESC)
 *     .limit(10)
 *     .offset(20)
 *     .list();
 * }</pre>
 *
 * @param <T> entity type
 */
public interface Query<T extends Model<T>> {

    /** Append conditions combined with implicit AND. No-op for empty input. */
    Query<T> where(Condition... conditions);

    /** Alias of {@link #where} for chained readability. */
    Query<T> and(Condition... conditions);

    /**
     * Append conditions combined with OR among themselves, then AND-ed into
     * the existing predicate. {@code or(c1, c2)} is equivalent to
     * {@code and(Condition.or(c1, c2))}.
     */
    Query<T> or(Condition... conditions);

    /**
     * Add an ORDER BY clause.
     *
     * @throws com.holo.framework.horm.core.HormException if {@code field}
     *         belongs to a different entity type than this query's {@code T}
     */
    Query<T> orderBy(TypedField<T, ?> field, Order direction);

    /**
     * Maximum number of rows returned by {@link #list} / {@link #findFirst}.
     *
     * @throws IllegalArgumentException if {@code limit < 0}
     */
    Query<T> limit(long limit);

    /**
     * Number of rows to skip before returning results.
     *
     * @throws IllegalArgumentException if {@code offset < 0}
     */
    Query<T> offset(long offset);

    /**
     * Eagerly fetch a relation via LEFT JOIN and populate the relation
     * collection on each result entity. Convenience alias for
     * {@link #leftJoin}.
     *
     * <p>Cartesian products from HAS_MANY/HAS_AND_BELONGS_TO_MANY are
     * de-duplicated by root entity id — each root appears once in the
     * result list with its relation collection fully populated.
     */
    Query<T> fetch(RelationField<T, ?> relation);

    /**
     * LEFT JOIN the relation target and populate the collection on each
     * result entity.
     */
    Query<T> leftJoin(RelationField<T, ?> relation);

    /**
     * INNER JOIN the relation target and populate the collection on each
     * result entity. Rows whose join target is null are excluded.
     */
    Query<T> innerJoin(RelationField<T, ?> relation);

    /**
     * Alias of {@link #innerJoin} (jOOQ-style default inner).
     */
    Query<T> join(RelationField<T, ?> relation);

    /**
     * Project the root entity to a subset of columns. The projection
     * <strong>must</strong> include the root entity's id field;
     * non-projected fields will be {@code null} on returned entities.
     *
     * <p>Projection applies only to the root entity ({@code t0}).
     * Relation targets fetched via {@link #fetch} are always read in full.
     *
     * @throws com.holo.framework.horm.core.HormException if the projection
     *         does not include the root entity's id field
     */
    Query<T> select(TypedField<T, ?>... fields);

    /** Execute and return all matching rows. */
    List<T> list();

    /** Execute and return the first matching row, or {@code Optional.empty()}. */
    Optional<T> findFirst();

    /** Execute {@code SELECT COUNT(*)} with the current WHERE clause (ignores orderBy/limit/offset). */
    long count();

    /** Return {@code true} if any row matches the current WHERE clause. */
    boolean exists();
}
