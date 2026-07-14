package com.holo.framework.horm.core.query;

import com.holo.framework.horm.meta.Row;
import com.holo.framework.horm.meta.query.Condition;
import com.holo.framework.horm.meta.query.expr.Expr;

import java.util.List;
import java.util.Optional;

/**
 * Projection query that returns rows / scalars instead of entities.
 * Obtained via {@link Query#selectExpr(Expr...)}.
 *
 * <p>Supports further {@code groupBy}/{@code having} calls after
 * {@code selectExpr}, enabling flexible call ordering.
 */
public interface ProjectionQuery {

    /** Append GROUP BY expressions. */
    ProjectionQuery groupBy(Expr<?>... expressions);

    /** Append HAVING conditions. */
    ProjectionQuery having(Condition... conditions);

    /** Execute and return all matching rows. */
    List<Row> listRows();

    /**
     * Single-column projection shortcut. Requires exactly one projection.
     * @throws IllegalArgumentException if projections length ≠ 1
     */
    <S> List<S> listScalar(Class<S> scalarType);

    /** Execute and return the first matching row. */
    Optional<Row> firstRow();

    /** Execute and return the first scalar value. */
    <S> Optional<S> firstScalar(Class<S> scalarType);
}
