package com.holo.framework.horm.meta;

import com.holo.framework.horm.meta.query.TypedField;
import com.holo.framework.horm.meta.query.expr.AggExpr;
import com.holo.framework.horm.meta.query.expr.Expr;
import com.holo.framework.horm.meta.query.expr.FuncExpr;
import com.holo.framework.horm.meta.query.expr.FunctionType;

/**
 * Resolves a column name from an {@link Expr} for use in {@link Row#get(Expr)}.
 * <p>Falls back to a hash of the SQL fragment when no alias or field name is available.
 */
public final class ExprColumnResolver {

    private ExprColumnResolver() {}

    /**
     * Resolve the column name for the given expression.
     * Priority: alias &gt; TypedField.column() &gt; target field column (for aggregates) &gt; SQL fragment hash.
     * <p>For non-alias cases, returns lowercase to match JDBC column label behavior.
     */
    public static String resolve(Expr<?> expr) {
        if (expr.alias() != null) {
            // 别名保持原始大小写，因为 JDBC 会保留 AS 子句中的大小写
            return expr.alias();
        }
        // TypedField: sqlFragment() returns column(), use that directly (already lowercase)
        if (expr instanceof TypedField<?, ?> field) {
            return field.column();
        }
        // For AggExpr, try to derive a name from the target field
        if (expr instanceof AggExpr<?> agg) {
            if (agg.target() != null) {
                String prefix = switch (agg.functionType()) {
                    case COUNT -> "count_";
                    case COUNT_DISTINCT -> "count_distinct_";
                    case SUM -> "sum_";
                    case AVG -> "avg_";
                    case MAX -> "max_";
                    case MIN -> "min_";
                    default -> "";
                };
                return prefix + agg.target().column();
            }
            // COUNT(*) has no target
            if (agg.functionType() == FunctionType.COUNT) {
                return "count";
            }
        }
        // For FuncExpr with no alias, fall back to fragment hash
        return "_expr_" + Integer.toHexString(expr.sqlFragment().hashCode());
    }
}
