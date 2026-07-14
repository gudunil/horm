package com.holo.framework.horm.meta.query.expr;

import com.holo.framework.horm.meta.query.TypedField;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Factory for aggregate {@link AggExpr} instances. Naming follows
 * the {@link com.holo.framework.horm.meta.query.Conditions} convention
 * (plural noun, final class, private constructor, static methods only).
 */
public final class Aggregates {

    private Aggregates() {}

    /** COUNT(*). */
    public static AggExpr<Long> count() {
        return new AggExprImpl<>(FunctionType.COUNT, null, Long.class, null);
    }

    /** COUNT(field). */
    public static AggExpr<Long> count(TypedField<?, ?> field) {
        return new AggExprImpl<>(FunctionType.COUNT, field, Long.class, null);
    }

    /** COUNT(DISTINCT field). */
    public static AggExpr<Long> countDistinct(TypedField<?, ?> field) {
        return new AggExprImpl<>(FunctionType.COUNT_DISTINCT, field, Long.class, null);
    }

    /**
     * SUM aggregate. Returns {@code BigDecimal} uniformly across dialects
     * to avoid integer overflow (MySQL SUM(int) → DECIMAL, PG SUM(bigint) → BIGINT).
     */
    public static <N extends Number> AggExpr<BigDecimal> sum(TypedField<?, N> field) {
        return new AggExprImpl<>(FunctionType.SUM, field, BigDecimal.class, null);
    }

    /** AVG aggregate. Returns {@code BigDecimal} uniformly. */
    public static <N extends Number> AggExpr<BigDecimal> avg(TypedField<?, N> field) {
        return new AggExprImpl<>(FunctionType.AVG, field, BigDecimal.class, null);
    }

    /** MAX aggregate. */
    public static <T extends Comparable<T>> AggExpr<T> max(TypedField<?, T> field) {
        return new AggExprImpl<>(FunctionType.MAX, field, field.type(), null);
    }

    /** MIN aggregate. */
    public static <T extends Comparable<T>> AggExpr<T> min(TypedField<?, T> field) {
        return new AggExprImpl<>(FunctionType.MIN, field, field.type(), null);
    }

    /**
     * Attach an alias to any aggregate expression (for projection naming).
     * Returns a new immutable {@code AggExpr}; the original is unchanged
     * (consistent with {@code TypedField} immutability).
     */
    public static <T> AggExpr<T> alias(AggExpr<T> expr, String alias) {
        Objects.requireNonNull(alias, "alias must not be null");
        return new AggExprImpl<>(expr.functionType(), expr.target(), expr.javaType(), alias);
    }

    private static final class AggExprImpl<T> implements AggExpr<T> {
        private final FunctionType functionType;
        private final TypedField<?, ?> target;
        private final Class<T> javaType;
        private final String alias;

        AggExprImpl(FunctionType functionType, TypedField<?, ?> target, Class<T> javaType, String alias) {
            this.functionType = functionType;
            this.target = target;
            this.javaType = javaType;
            this.alias = alias;
        }

        @Override public FunctionType functionType() { return functionType; }
        @Override public TypedField<?, ?> target() { return target; }
        @Override public Class<T> javaType() { return javaType; }
        @Override public String alias() { return alias; }

        @Override
        public String sqlFragment() {
            String targetCol = target != null ? target.column() : "*";
            return switch (functionType) {
                case COUNT -> "COUNT(" + targetCol + ")";
                case COUNT_DISTINCT -> "COUNT(DISTINCT " + targetCol + ")";
                case SUM -> "SUM(" + targetCol + ")";
                case AVG -> "AVG(" + targetCol + ")";
                case MAX -> "MAX(" + targetCol + ")";
                case MIN -> "MIN(" + targetCol + ")";
                default -> throw new IllegalStateException("Unexpected aggregate function: " + functionType);
            };
        }

        @Override
        public List<Object> bindings() {
            return Collections.emptyList();
        }

        @Override
        public String toString() {
            return sqlFragment() + (alias != null ? " AS " + alias : "");
        }
    }
}
